#!/usr/bin/env bash
# Downloads Chaquopy's CPython 3.12 standard library archive (MIT), strips desktop-only
# and test components unsuitable for Android, and writes a trimmed stdlib zip for APK assets.
#
# usage: stage-python-stdlib.sh <output zip>
source "$(dirname "$0")/common.sh"

out_zip=${1:?usage: stage-python-stdlib.sh <output zip>}

STDLIB_URL="https://repo1.maven.org/maven2/com/chaquo/python/target/3.12.12-0/target-3.12.12-0-stdlib.zip"
STDLIB_SHA="cfeb0137cb3ab149b0817d9ec15091e6e667db7151e4f79ad46b58b02a68f28c"

mkdir -p "$DOWNLOAD_DIR" "$(dirname "$out_zip")"
raw_zip="$DOWNLOAD_DIR/python-target-3.12.12-0-stdlib.zip"
if [ ! -f "$raw_zip" ] || ! echo "$STDLIB_SHA  $raw_zip" | sha256sum --check --status; then
    log "Downloading CPython 3.12.12 stdlib"
    curl -fsSL --retry 5 --retry-delay 10 -o "$raw_zip.part" "$STDLIB_URL"
    mv "$raw_zip.part" "$raw_zip"
    echo "$STDLIB_SHA  $raw_zip" | sha256sum --check --status || die "Checksum mismatch for $STDLIB_URL"
fi

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
unzip -q "$raw_zip" -d "$stage"

# Drop desktop-only, GUI, installer, legacy/migration, and test modules.
DROP_ENTRIES=(
    tkinter
    test
    idlelib
    ensurepip
    lib2to3
    turtledemo
    pydoc_data
    turtle.py
    venv
    unittest
    curses
    dbm
    distutils
    xmlrpc
    antigravity.py
    this.py
    pydoc.py
    doctest.py
)
for entry in "${DROP_ENTRIES[@]}"; do
    rm -rf "${stage:?}/$entry"
done

find "$stage" -type d \( -name test -o -name tests -o -name __pycache__ \) -prune -exec rm -rf {} +

python3 - "$stage" "$out_zip" <<'PY'
import os
import sys
import zipfile

stage, out_zip = sys.argv[1], sys.argv[2]
tmp_zip = out_zip + ".part"
with zipfile.ZipFile(tmp_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
    for root, dirs, files in os.walk(stage):
        dirs.sort()
        files.sort()
        for name in files:
            full = os.path.join(root, name)
            rel = os.path.relpath(full, stage).replace(os.sep, "/")
            zf.write(full, rel)
os.replace(tmp_zip, out_zip)
PY

log "Staged trimmed Python stdlib at $out_zip ($(du -h "$out_zip" | cut -f1))"
