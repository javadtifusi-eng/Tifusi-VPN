#!/usr/bin/env bash
# Installs the APK on the running emulator, opens it and fails unless the app is still alive after
# the first screen has been drawn. v23 shipped a crash in exactly that window, and because the
# in-app updater lives inside the app, users who took that update could not update their way out.
set -uo pipefail

APK="$1"
PREVIOUS="${2:-}"
PKG="com.tifusi.vpn"
WAIT_SECONDS=20

adb wait-for-device

# Install the build customers have now, then this one over it, the way the in-app updater does. A
# changed or broken signature fails right here (INSTALL_FAILED_UPDATE_INCOMPATIBLE), before release.
if [ -n "$PREVIOUS" ] && [ -f "$PREVIOUS" ]; then
  if ! adb install "$PREVIOUS"; then
    echo "::error::Installing the currently published APK on the emulator failed"
    exit 1
  fi
  echo "Published build installed; installing this build over it."
fi

if ! adb install -r "$APK"; then
  echo "::error::Installing $APK on the emulator failed (over the published build, if one was found)"
  exit 1
fi

# After an upgrade from a build signed only with the old public key, that build must no longer
# install over this one — the reason for rotating. Only checked while the published build is still
# an old-key one (PREVIOUS_OLD_KEY, set by the workflow); later builds share the new key.
if [ "${PREVIOUS_OLD_KEY:-}" = "1" ]; then
  out="$(adb install -r -d "$PREVIOUS" 2>&1)"
  if echo "$out" | grep -q "^Success"; then
    echo "::error::An APK signed only with the old public key still installs over this build"
    exit 1
  elif echo "$out" | grep -q "UPDATE_INCOMPATIBLE"; then
    echo "Old-key APK refused over this build, as intended."
  else
    echo "::warning::Old-key reinstall check inconclusive: $out"
  fi
fi

adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep "$WAIT_SECONDS"

crash="$(adb logcat -d -b crash)"
if [ -n "$crash" ] || ! adb shell pidof "$PKG" >/dev/null; then
  echo "::error::Tifusi VPN crashed or exited within ${WAIT_SECONDS}s of launch"
  echo "$crash"
  adb logcat -d -s AndroidRuntime:E | tail -n 80
  exit 1
fi

echo "Tifusi VPN is still running ${WAIT_SECONDS}s after launch."
