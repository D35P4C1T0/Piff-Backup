# Third-party notices

## rsync 3.4.2

PiffBackup distributes an unmodified, cross-compiled rsync 3.4.2 executable.
Rsync is copyright its contributors and licensed under GNU GPL version 3 or
later. The exact corresponding source archive is available from
`https://download.samba.org/pub/rsync/src/rsync-3.4.2.tar.gz`; its SHA-256 is
recorded in `tools/native/checksums.txt`, and `tools/native/build-rsync.sh`
contains the complete reproduction procedure. The complete GPLv3 license is in
the upstream archive's `COPYING` file and at `https://www.gnu.org/licenses/gpl-3.0.txt`.

No rsync source modifications are currently applied. Optional OpenSSL, xxhash,
zstd, lz4, and iconv support is disabled in this build.

## Dropbear 2025.89

PiffBackup distributes unmodified `dbclient`, `dropbearkey`, and
`dropbearconvert` executables from Dropbear 2025.89. The exact corresponding
source archive is available from
`https://dropbear.nl/mirror/releases/dropbear-2025.89.tar.bz2`; its SHA-256 is
recorded in `tools/native/checksums.txt`, and
`tools/native/build-ssh-client.sh` contains the reproduction procedure.

Copyright © 2002–2020 Matt Johnston. Portions copyright © 2004 Mihnea
Stoenescu. The primary Dropbear license permits use, copying, modification,
merging, publication, distribution, sublicensing, and sale, provided its
copyright and permission notice are retained. The software is supplied without
warranty. Dropbear also contains separately noticed LibTomCrypt, LibTomMath,
OpenSSH, PuTTY, and public-domain components. Their complete notices are
preserved in the exact source archive's `LICENSE`, `libtomcrypt/LICENSE`, and
`libtommath/LICENSE` files.
