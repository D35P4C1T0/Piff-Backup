#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
abis=${PIFFBACKUP_ABIS:-"arm64-v8a armeabi-v7a x86_64 x86"}
for abi in $abis; do
    PIFFBACKUP_ABI="$abi" "$script_dir/build-rsync.sh"
    PIFFBACKUP_ABI="$abi" "$script_dir/build-ssh-client.sh"
done
printf '%s\n' "Android tools written to app/src/main/jniLibs"
