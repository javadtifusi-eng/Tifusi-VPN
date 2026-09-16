#!/usr/bin/env bash
# Installs the APK on the running emulator, opens it and fails unless the app is still alive after
# the first screen has been drawn. v23 shipped a crash in exactly that window, and because the
# in-app updater lives inside the app, users who took that update could not update their way out.
set -uo pipefail

APK="$1"
PKG="com.tifusi.vpn"
WAIT_SECONDS=20

adb wait-for-device
if ! adb install -r "$APK"; then
  echo "::error::Installing $APK on the emulator failed"
  exit 1
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
