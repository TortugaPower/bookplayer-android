#!/usr/bin/env bash
# Fill the emulator's /data partition so the app sees no free space (Sentry ANDROID-BOOKPLAYER-12 / -10 /
# -1D / -1G / -S / -V), or free it again.
#
# Usage: scripts/chaos/fill-disk.sh fill [leave-kb]     # default leaves 0 KB (needs a rootable image)
#        scripts/chaos/fill-disk.sh free                 # frees the last few hundred MB; keeps the ballast
#        scripts/chaos/fill-disk.sh free --all           # also removes the ballast
# Env:   ADB=/path/to/adb (default: adb on PATH)
#
# Two layers: a BALLAST that takes the volume down to ~512 MB and stays across fill/free cycles, and a
# small fill on top that `free` removes. Freeing a multi-GB written file makes the kernel discard every
# block, which stalls the emulator's storage for minutes: the app, systemui and system_server all freeze
# (Choreographer "Skipped 8000+ frames", ANR dumps taking 100+ s, a system_server watchdog restart) and a
# sync-host start issued just before the freeze fakes ANDROID-BOOKPLAYER-1H. Keeping the ballast means
# `free` only touches a few hundred MB.
#
# The ballast is `fallocate`d (unwritten extents, cheap to free) with a one-time `dd` fallback; the top
# fill is `fallocate` + `dd` until ENOSPC for the last blocks (as root, so the app — a normal user — sees
# exactly 0). To reproduce the database-open failure at launch, also remove the app's `-shm`/`-wal` files
# first so SQLite has to size its shared memory again (this discards the WAL's unflushed rows — test data
# only):
#   adb shell "run-as com.tortugapower.audiobookplayer sh -c 'rm -f databases/bookplayer.db-shm databases/bookplayer.db-wal'"
#
# Root vs app view: `df` here runs as root and counts the filesystem's root-reserved blocks (~140 MB on
# the 6 GB test volume); the app's StatFs does not. "leave-kb" is the ROOT figure — the app sees about
# 140 MB less. Check the app's view with:
#   adb shell "run-as com.tortugapower.audiobookplayer df -k /data/data/com.tortugapower.audiobookplayer"
set -euo pipefail

MODE=${1:?usage: fill-disk.sh fill [leave-kb] | free [--all]}
ARG=${2:-}
ADB=${ADB:-adb}
TMP=/data/local/tmp
BALLAST_FLOOR_KB=$((512 * 1024))

free_kb() { "$ADB" shell df -k /data | awk 'NR==2 {print $4}'; }
# Allocate $2 KB at $1: fallocate (cheap to free) or, if the filesystem refuses, a plain write.
alloc() {
  "$ADB" shell "fallocate -l $(( $2 * 1024 )) $1" 2>/dev/null \
    || "$ADB" shell "dd if=/dev/zero of=$1 bs=1048576 count=$(( $2 / 1024 )) 2>/dev/null; true"
}

case "$MODE" in
  fill)
    LEAVE_KB=${ARG:-0}
    "$ADB" root >/dev/null 2>&1 || true; sleep 2; "$ADB" wait-for-device
    before=$(free_kb); echo "free before: $((before / 1024)) MB"
    if [ "$before" -gt $(( BALLAST_FLOOR_KB + LEAVE_KB )) ] && ! "$ADB" shell "test -e $TMP/ballast.bin" 2>/dev/null; then
      alloc "$TMP/ballast.bin" $(( before - BALLAST_FLOOR_KB )); echo "ballast: $(( (before - BALLAST_FLOOR_KB) / 1024 )) MB (kept by 'free')"
    fi
    before=$(free_kb)
    bulk_kb=$(( before - LEAVE_KB - 1024 ))
    [ "$bulk_kb" -gt 0 ] && alloc "$TMP/fill.bin" "$bulk_kb"
    if [ "$LEAVE_KB" -eq 0 ]; then
      # take the remainder block by block until the volume reports no free space
      "$ADB" shell "dd if=/dev/zero of=$TMP/fill2.bin bs=4096 2>/dev/null; true"
    fi
    echo "free after: $(free_kb) KB" ;;
  free)
    "$ADB" shell rm -f "$TMP/fill.bin" "$TMP/fill2.bin"
    [ "$ARG" = "--all" ] && "$ADB" shell rm -f "$TMP/ballast.bin"
    echo "free now: $(( $(free_kb) / 1024 )) MB" ;;
  *) echo "unknown mode $MODE"; exit 1 ;;
esac
