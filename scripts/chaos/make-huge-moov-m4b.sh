#!/usr/bin/env bash
# Build an m4b whose `moov` box is huge WITHOUT cover art: a trailing `free` child is appended inside
# moov. This isolates the chapter extractor (Sentry ANDROID-BOOKPLAYER-17/-18) from the artwork step,
# which also allocates a cover-sized buffer and would otherwise fail first on a tight heap.
#
# Usage: scripts/chaos/make-huge-moov-m4b.sh <in.m4b> <out.m4b> <megabytes>
# The input's moov must be its last top-level box (true for the chapterfixtures m4b files and for
# most ffmpeg/mutagen output), so growing it moves no sample offsets.
set -euo pipefail

IN=${1:?usage: make-huge-moov-m4b.sh <in.m4b> <out.m4b> <megabytes>}
OUT=${2:?}
MB=${3:?}

python3 - "$IN" "$OUT" "$MB" <<'PY'
import struct, sys
src = open(sys.argv[1], "rb").read()
off, moov = 0, None
while off + 8 <= len(src):
    n = struct.unpack(">I", src[off:off + 4])[0]
    if src[off + 4:off + 8] == b"moov": moov = (off, n)
    if n < 8: break
    off += n
if not moov or moov[0] + moov[1] != len(src):
    sys.exit("moov must be the last top-level box of the input")
pad = int(float(sys.argv[3]) * 2**20)
out = bytearray(src)
out[moov[0]:moov[0] + 4] = struct.pack(">I", moov[1] + 8 + pad)
out += struct.pack(">I", 8 + pad) + b"free" + bytes(pad)
open(sys.argv[2], "wb").write(out)
print(f"{sys.argv[2]}: {len(out) / 2**20:.1f} MB, moov = {(moov[1] + 8 + pad) / 2**20:.1f} MB, no cover art")
PY
