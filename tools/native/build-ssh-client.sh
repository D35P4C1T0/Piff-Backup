#!/bin/sh
set -eu
. "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/common.sh"

DROPBEAR_VERSION=2025.89
ARCHIVE="dropbear-$DROPBEAR_VERSION.tar.bz2"
download_and_verify "$ARCHIVE" "https://dropbear.nl/mirror/releases/$ARCHIVE"
configure_toolchain

source_dir="$BUILD_DIR/dropbear-$DROPBEAR_VERSION"
rm -rf "$source_dir"
mkdir -p "$BUILD_DIR" "$OUTPUT_DIR"
tar -xjf "$DOWNLOAD_DIR/$ARCHIVE" -C "$BUILD_DIR"

cd "$source_dir"
patch -p1 < "$NATIVE_DIR/dropbear-rsa-hostkey-first.patch"
build_triplet=$(sh ./src/config.guess)
./configure \
    --build="$build_triplet" \
    --host=aarch64-linux-android \
    --disable-zlib \
    --disable-syslog \
    --disable-shadow \
    --disable-lastlog \
    --disable-utmp \
    --disable-utmpx \
    --disable-wtmp \
    --disable-wtmpx \
    --disable-loginfunc \
    --disable-pututline \
    --disable-pututxline \
    CFLAGS="-O2 -fPIE -fstack-protector-strong -D_FORTIFY_SOURCE=2" \
    LDFLAGS="-pie -Wl,-z,relro,-z,now"
cp "$NATIVE_DIR/dropbear-localoptions.h" localoptions.h
make -j"${PIFFBACKUP_BUILD_JOBS:-4}" PROGRAMS="dbclient dropbearkey dropbearconvert" MULTI=0

for pair in \
    "dbclient:libpiffbackup_dbclient.so" \
    "dropbearkey:libpiffbackup_dropbearkey.so" \
    "dropbearconvert:libpiffbackup_dropbearconvert.so"
do
    source_name=${pair%%:*}
    output_name=${pair#*:}
    "$STRIP" --strip-unneeded "$source_name"
    verify_android_executable "$source_name"
    cp "$source_name" "$OUTPUT_DIR/$output_name"
    chmod 0755 "$OUTPUT_DIR/$output_name"
done
