#!/bin/bash
# Builds strongSwan's charon for Android (libandroidbridge, libcharon, libipsec, libstrongswan)
# into app/src/main/jniLibs, the IKEv2 engine behind org.strongswan.android.logic.CharonVpnService.
# Needs ANDROID_NDK_ROOT. Sources are fetched with pinned checksums, like libv2ray.aar.
set -euo pipefail

STRONGSWAN_VERSION=6.0.2
STRONGSWAN_SHA256=f8cc16057eedcab5a542d732f04c8522232254426fa57570f18598de38e06e62
OPENSSL_VERSION=3.5.8
OPENSSL_SHA256=a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2
ABIS="arm64-v8a armeabi-v7a"
MIN_SDK=21

: "${ANDROID_NDK_ROOT:?ANDROID_NDK_ROOT is not set}"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
JNI=$ROOT/app/src/main/jni
WORK=$ROOT/app/build-native
mkdir -p "$WORK"

fetch() { # url sha256 out
  [ -f "$3" ] && echo "$2  $3" | sha256sum -c - >/dev/null 2>&1 && return
  curl -fsSL --retry 3 -o "$3" "$1"
  echo "$2  $3" | sha256sum -c -
}

fetch "https://download.strongswan.org/strongswan-${STRONGSWAN_VERSION}.tar.gz" "$STRONGSWAN_SHA256" "$WORK/strongswan.tar.gz"
fetch "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz" "$OPENSSL_SHA256" "$WORK/openssl.tar.gz"

rm -rf "$JNI/strongswan-src" && mkdir -p "$JNI/strongswan-src"
tar -xzf "$WORK/strongswan.tar.gz" -C "$JNI/strongswan-src" --strip-components=1

# OpenSSL's libcrypto, static, per ABI; the options are strongSwan's (src/frontends/android/openssl/compile.sh).
OUT=$JNI/openssl
rm -rf "$OUT" "$WORK/openssl-src" && mkdir -p "$OUT" "$WORK/openssl-src"
tar -xzf "$WORK/openssl.tar.gz" -C "$WORK/openssl-src" --strip-components=1
export PATH=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
export ANDROID_NDK_HOME=$ANDROID_NDK_ROOT
(
  cd "$WORK/openssl-src"
  for ABI in $ABIS; do
    case $ABI in
      armeabi-v7a) TARGET=android-arm ;;
      arm64-v8a) TARGET=android-arm64 ;;
      x86_64) TARGET=android-x86_64 ;;
    esac
    echo "## OpenSSL libcrypto for $ABI"
    make distclean >/dev/null 2>&1 || true
    ./Configure $TARGET \
      no-shared no-ct no-cast no-comp no-dgram no-dsa no-gost no-idea \
      no-rmd160 no-seed no-sm2 no-sm3 no-sm4 no-sock no-srp no-srtp \
      no-err no-engine no-dso no-hw no-stdio no-ui-console no-docs no-apps no-tests \
      -fPIC -DOPENSSL_PIC -O3 -Wno-macro-redefined -D__ANDROID_API__=$MIN_SDK >/dev/null
    make -j"$(nproc)" build_generated >/dev/null
    make -j"$(nproc)" libcrypto.a >/dev/null
    mkdir -p "$OUT/$ABI" && cp libcrypto.a "$OUT/$ABI/"
  done
  cp -R include/ "$OUT/"
)
cat > "$OUT/Android.mk" <<'MK'
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libcrypto_static
LOCAL_SRC_FILES := $(TARGET_ARCH_ABI)/libcrypto.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
include $(PREBUILT_STATIC_LIBRARY)
MK

echo "## strongSwan"
"$ANDROID_NDK_ROOT/ndk-build" -j"$(nproc)" \
  NDK_PROJECT_PATH="$WORK/ndk" APP_BUILD_SCRIPT="$JNI/Android.mk" NDK_APPLICATION_MK="$JNI/Application.mk" \
  APP_ABI="$ABIS" NDK_OUT="$WORK/ndk/obj" NDK_LIBS_OUT="$WORK/ndk/libs"

for ABI in $ABIS; do
  mkdir -p "$ROOT/app/src/main/jniLibs/$ABI"
  cp "$WORK/ndk/libs/$ABI/"*.so "$ROOT/app/src/main/jniLibs/$ABI/"
done
ls -la "$ROOT"/app/src/main/jniLibs/*/
