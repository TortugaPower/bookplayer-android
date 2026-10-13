#!/usr/bin/env bash
# Sentry ANDROID-BOOKPLAYER-23: add a bookmark while the book's library_items row is gone.
#
# Usage: scripts/chaos/bookmark-vanished-book.sh [audio-file] [package]
# Env:   ADB=/path/to/adb (default: adb on PATH)
#
# The player keeps its LibraryItemEntity in memory; a sync pull can delete/replace the row underneath
# it (uuid churn). `bookmarks.bookUuid` is a FOREIGN KEY to `library_items.uuid`, so the next
# "Create bookmark" tap fails the insert. The in-app delete path cannot produce this (it stops playback
# first) — the sync pull is simulated here by deleting the row with the image's sqlite3 while the
# player screen is open. Needs a debuggable build (run-as) and an emulator image that ships sqlite3
# (the google_apis images do).
#
# Before the fix: `FATAL EXCEPTION … SQLiteConstraintException: FOREIGN KEY constraint failed` at
#                 `LibraryDao_Impl.insertBookmark`, the process dies.
# After the fix:  no crash, no new bookmarks row, the player stays open.
set -euo pipefail

FILE=${1:-core/src/test/resources/chapterfixtures/m4b_WELLFORMED.m4b}
PKG=${2:-com.tortugapower.audiobookplayer}
ADB=${ADB:-adb}
HERE=$(cd "$(dirname "$0")" && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Tap the first node whose text or content-desc CONTAINS $1 (Compose merges a library row's title,
# author and duration into one label); retry for up to $2 seconds.
tap_match() {
  local needle=$1 deadline=$((SECONDS + $2)) xy
  while [ "$SECONDS" -lt "$deadline" ]; do
    "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    xy=$("$ADB" exec-out cat /sdcard/ui.xml 2>/dev/null | python3 -c '
import re, sys, xml.etree.ElementTree as ET
try: root = ET.fromstring(sys.stdin.read())
except Exception: sys.exit(0)
for n in root.iter("node"):
    if sys.argv[1] in (n.get("content-desc") or "") + "|" + (n.get("text") or ""):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2); break' "$needle")
    if [ -n "$xy" ]; then "$ADB" shell input tap $xy; echo "tapped '$needle' at ($xy)"; return 0; fi
    sleep 1
  done
  echo "never saw '$needle'"; return 1
}

db_query() {  # $1 = SQL; reads a copy of the app DB (+WAL) pulled through run-as
  "$ADB" exec-out run-as "$PKG" cat databases/bookplayer.db > "$TMP/db" 2>/dev/null || true
  "$ADB" exec-out run-as "$PKG" cat databases/bookplayer.db-wal > "$TMP/db-wal" 2>/dev/null || true
  python3 -c 'import sqlite3, sys; print(sqlite3.connect(sys.argv[1]).execute(sys.argv[2]).fetchone()[0])' "$TMP/db" "$1"
}

TITLE=$(basename "${FILE%.*}")
"$ADB" shell am force-stop "$PKG"
"$ADB" shell pm clear "$PKG" >/dev/null            # same-named files are deduplicated on import
ADB="$ADB" "$HERE/import-file.sh" "$FILE" "$PKG" | tail -1
UUID=$(db_query "select uuid from library_items limit 1")
[ -n "$UUID" ] || { echo "import produced no library row"; exit 1; }

tap_match "$TITLE." 20            # open the book: the player sheet expands and playback starts
sleep 4
tap_match "Create bookmark" 10    # baseline: a bookmark while the row exists must succeed
sleep 2
"$ADB" shell input keyevent 4     # dismiss the "bookmark saved" dialog
sleep 1
tap_match "Rewind 30 seconds" 5 || true   # move the position so the next tap takes the insert path
BEFORE=$(db_query "select count(*) from bookmarks")

# The sync pull: the row disappears while the player still holds the item.
"$ADB" shell "run-as $PKG sqlite3 databases/bookplayer.db \"DELETE FROM library_items WHERE uuid='$UUID'\""
echo "library_items rows now: $(db_query 'select count(*) from library_items')  (bookmarks before: $BEFORE)"

"$ADB" logcat -c
tap_match "Create bookmark" 10
sleep 4
FATAL=$("$ADB" logcat -d -v brief | grep -c "FATAL EXCEPTION" || true)
ALIVE=$( ("$ADB" shell pidof "$PKG" 2>/dev/null || true) | wc -w | tr -d ' ')
AFTER=$(db_query "select count(*) from bookmarks")
echo "result: fatal=$FATAL alive=$ALIVE bookmarks ${BEFORE}→${AFTER}"
if [ "$FATAL" != "0" ]; then
  "$ADB" logcat -d -v brief | grep -A30 "FATAL EXCEPTION" \
    | grep -E "FATAL|Exception|at com.tortugapower" | sed 's/^E\/AndroidRuntime([ 0-9]*): //' | head -6
fi
