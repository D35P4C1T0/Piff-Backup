#!/bin/sh
set -eu

NATIVE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$NATIVE_DIR/../.." && pwd)
DOWNLOAD_DIR=${PIFFBACKUP_DOWNLOAD_DIR:-"$NATIVE_DIR/downloads"}
BUILD_DIR=${PIFFBACKUP_BUILD_DIR:-"$NATIVE_DIR/.build"}
OUTPUT_DIR=${PIFFBACKUP_OUTPUT_DIR:-"$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"}
ANDROID_API=33
NDK_VERSION=28.2.13676358

find_ndk() {
    if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
        printf '%s\n' "$ANDROID_NDK_ROOT"
        return
    fi
    if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/ndk/$NDK_VERSION" ]; then
        printf '%s\n' "$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
        return
    fi
    sdk_dir=$(sed -n 's/^sdk.dir=//p' "$PROJECT_DIR/local.properties" 2>/dev/null | tail -n 1)
    if [ -n "$sdk_dir" ] && [ -d "$sdk_dir/ndk/$NDK_VERSION" ]; then
        printf '%s\n' "$sdk_dir/ndk/$NDK_VERSION"
        return
    fi
    printf '%s\n' "Android NDK $NDK_VERSION not found. Install it in Android Studio or set ANDROID_NDK_ROOT." >&2
    exit 1
}

configure_toolchain() {
    NDK_DIR=$(find_ndk)
    case $(uname -s) in
        Darwin) host_tag=darwin-x86_64 ;;
        Linux) host_tag=linux-x86_64 ;;
        *) printf '%s\n' "Unsupported build host: $(uname -s)" >&2; exit 1 ;;
    esac
    TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$host_tag"
    if [ ! -x "$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang" ]; then
        printf '%s\n' "NDK compiler not found under $TOOLCHAIN" >&2
        exit 1
    fi
    export NDK_DIR TOOLCHAIN
    export CC="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang"
    export AR="$TOOLCHAIN/bin/llvm-ar"
    export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export STRIP="$TOOLCHAIN/bin/llvm-strip"
    export READELF="$TOOLCHAIN/bin/llvm-readelf"
}

download_and_verify() {
    archive=$1
    url=$2
    mkdir -p "$DOWNLOAD_DIR"
    if [ ! -f "$DOWNLOAD_DIR/$archive" ]; then
        curl --fail --location --proto '=https' --tlsv1.2 "$url" -o "$DOWNLOAD_DIR/$archive"
    fi
    expected=$(awk -v file="$archive" '$2 == file { print $1 }' "$NATIVE_DIR/checksums.txt")
    if [ -z "$expected" ]; then
        printf '%s\n' "No checksum recorded for $archive" >&2
        exit 1
    fi
    actual=$(shasum -a 256 "$DOWNLOAD_DIR/$archive" | awk '{ print $1 }')
    if [ "$actual" != "$expected" ]; then
        printf '%s\n' "Checksum mismatch for $archive" >&2
        exit 1
    fi
}

verify_android_executable() {
    executable=$1
    "$READELF" -h "$executable" | grep -q 'Class:.*ELF64'
    "$READELF" -h "$executable" | grep -q 'Type:.*DYN'
    "$READELF" -h "$executable" | grep -q 'Machine:.*AArch64'
    "$READELF" -l "$executable" | grep -q 'Requesting program interpreter: /system/bin/linker64'
}
