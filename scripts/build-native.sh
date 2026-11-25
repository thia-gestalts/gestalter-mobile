#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}}
ndk=${ANDROID_NDK_HOME:-"$sdk/ndk/28.2.13676358"}
clang="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
mkdir -p build/jni

if (( $# == 0 )); then set -- arm64-v8a x86_64; fi
for name in "$@"; do
    case "$name" in
      arm64-v8a) rust=aarch64-linux-android; target=aarch64-linux-android ;;
      armeabi-v7a) rust=armv7-linux-androideabi; target=armv7a-linux-androideabi ;;
      x86_64) rust=x86_64-linux-android; target=x86_64-linux-android ;;
      x86) rust=i686-linux-android; target=i686-linux-android ;;
      *) echo "Unsupported Android ABI $name" >&2; exit 1 ;;
    esac
    suffix=${rust//-/_}
    cc="$clang/${target}26-clang"
    env "CARGO_TARGET_${suffix^^}_LINKER=$cc" "CC_$suffix=$cc" "AR_$suffix=$clang/llvm-ar" \
      cargo build --manifest-path native/Cargo.toml --locked --release --target "$rust"
    mkdir -p "build/jni/lib/$name"
    "$clang/llvm-strip" --strip-unneeded "native/target/$rust/release/libgestalter_crypto.so" \
      -o "build/jni/lib/$name/libgestalter_crypto.so"
done
