#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
"$script_dir/build-rsync.sh"
"$script_dir/build-ssh-client.sh"
printf '%s\n' "ARM64 Android tools written to app/src/main/jniLibs/arm64-v8a"
