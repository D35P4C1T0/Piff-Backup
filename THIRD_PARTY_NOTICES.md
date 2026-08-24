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

## SSHJ 0.40.0 and onboarding dependencies

PiffBackup uses SSHJ 0.40.0 only for the one-time password-authenticated
`install-ssh-key` onboarding command. SSHJ is copyright its contributors and
licensed under the Apache License 2.0. Source and license:
`https://github.com/hierynomus/sshj/tree/v0.40.0`.

SSHJ brings these runtime dependencies into the APK:

- Bouncy Castle `bcprov-jdk18on` 1.80.2, `bcpkix-jdk18on` 1.80, and
  `bcutil-jdk18on` 1.80.2, distributed under the Bouncy Castle licence at
  `https://www.bouncycastle.org/about/licence.html`.
- `com.hierynomus:asn-one` 0.6.0, licensed under Apache License 2.0; source at
  `https://github.com/hierynomus/asn-one`.
- SLF4J API/NOP 2.0.18, copyright its contributors and licensed under the MIT
  License; source and licence at `https://www.slf4j.org/license.html`.

Apache License 2.0 is available at
`https://www.apache.org/licenses/LICENSE-2.0`. These Java libraries are not
used for ongoing file transfer; packaged Dropbear remains the strict native
transport after onboarding.
