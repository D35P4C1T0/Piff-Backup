#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/common.sh"

RSYNC_VERSION=3.4.2
ARCHIVE="rsync-$RSYNC_VERSION.tar.gz"
download_and_verify "$ARCHIVE" "https://download.samba.org/pub/rsync/src/$ARCHIVE"
configure_toolchain

source_dir="$BUILD_DIR/rsync-$RSYNC_VERSION"
rm -rf "$source_dir"
mkdir -p "$BUILD_DIR" "$OUTPUT_DIR"
tar -xzf "$DOWNLOAD_DIR/$ARCHIVE" -C "$BUILD_DIR"

cd "$source_dir"
build_triplet=$(sh ./config.guess)
ac_cv_func_getpass=no \
    ./configure \
    --build="$build_triplet" \
    --host=aarch64-linux-android \
    --with-included-popt \
    --disable-openssl \
    --disable-xxhash \
    --disable-zstd \
    --disable-lz4 \
    --disable-iconv \
    CFLAGS="-O2 -fPIE -fstack-protector-strong -D_FORTIFY_SOURCE=2" \
    LDFLAGS="-pie -Wl,-z,relro,-z,now"
make -j"${PIFFBACKUP_BUILD_JOBS:-4}" rsync
"$STRIP" --strip-unneeded rsync
verify_android_executable rsync
cp rsync "$OUTPUT_DIR/libpiffbackup_rsync.so"
chmod 0755 "$OUTPUT_DIR/libpiffbackup_rsync.so"
