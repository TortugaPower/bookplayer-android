#!/usr/bin/env bash
# Build an m4b whose `moov` box is huge, the shape behind Sentry ANDROID-BOOKPLAYER-17/-18: embed an
# incompressible PNG of the requested size as cover art (MP4 cover art lives inside moov/udta).
#
# Usage: scripts/chaos/make-huge-cover-m4b.sh <in.m4b> <out.m4b> <cover-megabytes>
# Needs: python3 with mutagen (python3 -m pip install --user mutagen)
#
# Good inputs: core/src/test/resources/chapterfixtures/m4b_MALFORMED.m4b (4 chapters the manual
# parser recovers) or m4b_WELLFORMED.m4b. mutagen rewrites moov and fixes stco offsets, so the
# result stays playable and the chapter track is untouched.
set -euo pipefail

IN=${1:?usage: make-huge-cover-m4b.sh <in.m4b> <out.m4b> <cover-megabytes>}
OUT=${2:?}
MB=${3:?}

python3 - "$IN" "$OUT" "$MB" <<'PY'
import math, os, shutil, struct, sys, zlib
try:
    from mutagen.mp4 import MP4, MP4Cover
except ImportError:
    sys.exit("mutagen is missing: python3 -m pip install --user mutagen")

src, dst, mb = sys.argv[1], sys.argv[2], float(sys.argv[3])

def noise_png(target_bytes):
    side = int(math.sqrt(target_bytes / 3))
    row = b"\x00" + os.urandom(side * 3)              # filter byte + RGB noise: incompressible
    raw = row * side
    def chunk(tag, data):
        body = struct.pack(">I", len(data)) + tag + data
        return body + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    z = zlib.compressobj(0)                             # level 0 = stored blocks, size ≈ raw
    idat = z.compress(raw) + z.flush()
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", side, side, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", idat) + chunk(b"IEND", b""))

shutil.copy(src, dst)
f = MP4(dst)
f.tags["covr"] = [MP4Cover(noise_png(int(mb * 2**20)), imageformat=MP4Cover.FORMAT_PNG)]
f.save()

# Report the top-level layout so the moov size is visible.
with open(dst, "rb") as fh:
    fh.seek(0, 2); size = fh.tell(); off = 0; boxes = []
    while off + 8 <= size:
        fh.seek(off); hdr = fh.read(16)
        n = struct.unpack(">I", hdr[:4])[0]; t = hdr[4:8].decode("latin1")
        if n == 1: n = struct.unpack(">Q", hdr[8:16])[0]
        if n == 0: n = size - off
        boxes.append(f"{t}={n / 2**20:.1f}MB"); off += n
print(f"{dst}: {size / 2**20:.1f} MB  [" + " ".join(boxes) + "]")
PY
