#!/usr/bin/env bash
# Import a local audio file into the app on the connected emulator/device the way a user does
# ("Open with" → import sheet → Accept → place in Library), then report crashes and resulting rows.
#
# Usage: scripts/chaos/import-file.sh <audio-file> [package]
# Env:   ADB=/path/to/adb (default: adb on PATH)
#
# The file is copied into the app's private files dir (via run-as, so the build must be debuggable)
# and opened with an ACTION_VIEW file:// intent. Pushing to /sdcard/Android/data/<pkg> does NOT work
# after `adb root` — the app gets EACCES on the root-owned file.
set -euo pipefail

FILE=${1:?usage: import-file.sh <audio-file> [package]}
PKG=${2:-com.tortugapower.audiobookplayer}
ADB=${ADB:-adb}
NAME=$(basename "$FILE")
DB_TMP=$(mktemp -d)
trap 'rm -rf "$DB_TMP"' EXIT

# Tap the first accessibility node whose text or content-desc equals $1; retry for up to $2 seconds.
tap_node() {
  local label=$1 deadline=$((SECONDS + $2)) xy
  while [ "$SECONDS" -lt "$deadline" ]; do
    "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    xy=$("$ADB" exec-out cat /sdcard/ui.xml 2>/dev/null | python3 -c '
import re, sys, xml.etree.ElementTree as ET
try: root = ET.fromstring(sys.stdin.read())
except Exception: sys.exit(0)
for n in root.iter("node"):
    if sys.argv[1] in (n.get("text"), n.get("content-desc")):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2); break' "$label")
    if [ -n "$xy" ]; then "$ADB" shell input tap $xy; echo "tapped '$label' at ($xy)"; return 0; fi
    sleep 2
  done
  echo "never saw a node labelled '$label'"; return 1
}

# library_items / chapters counts from the app's Room DB (pulled through run-as).
db_counts() {
  "$ADB" exec-out run-as "$PKG" cat databases/bookplayer.db > "$DB_TMP/db" 2>/dev/null || true
  "$ADB" exec-out run-as "$PKG" cat databases/bookplayer.db-wal > "$DB_TMP/db-wal" 2>/dev/null || true
  python3 - "$DB_TMP/db" <<'PY'
import sqlite3, sys
try:
    c = sqlite3.connect(sys.argv[1])
    print(c.execute("select count(*) from library_items").fetchone()[0], c.execute("select count(*) from chapters").fetchone()[0])
except Exception:
    print("0 0")   # no database yet (fresh install / pm clear)
PY
}

stop_logcat() { { kill "$LOGCAT_PID"; wait "$LOGCAT_PID"; } 2>/dev/null || true; }

read -r ITEMS_BEFORE CHAPTERS_BEFORE <<<"$(db_counts)"

"$ADB" push "$FILE" "/data/local/tmp/$NAME" >/dev/null
"$ADB" shell "run-as $PKG sh -c 'mkdir -p files && cp /data/local/tmp/$NAME files/$NAME'"   # files/ is absent right after `pm clear`
"$ADB" shell am force-stop "$PKG"
# Stream logcat to a file for the whole run: the ring buffer wraps within a minute on this app
# (Wear publisher stack traces), which silently drops the crash we are looking for.
"$ADB" logcat -c
"$ADB" logcat -v brief > "$DB_TMP/logcat.txt" 2>/dev/null &
LOGCAT_PID=$!
trap 'stop_logcat; rm -rf "$DB_TMP"' EXIT
"$ADB" shell am start -W -a android.intent.action.VIEW -d "file:///data/data/$PKG/files/$NAME" \
  -t audio/mp4 -n "$PKG/.MainActivity" >/dev/null

# A missing control is itself a finding (the app may have died), so keep going and report below.
tap_node Accept 30 || true  # import sheet
tap_node Library 90 || true # "Import Complete — where to place?" → createBookItem runs after this

# Wait for either a crash or the new row.
for _ in $(seq 1 36); do
  sleep 5
  grep -q "FATAL EXCEPTION" "$DB_TMP/logcat.txt" && break
  read -r items _ <<<"$(db_counts)"
  [ "$items" -gt "$ITEMS_BEFORE" ] && break
done
stop_logcat

FATAL=$(grep -c "FATAL EXCEPTION" "$DB_TMP/logcat.txt" || true)
ALIVE=$( ("$ADB" shell pidof "$PKG" 2>/dev/null || true) | wc -w | tr -d ' ')   # pidof exits 1 when the app is dead; pipefail must not abort us
read -r ITEMS_AFTER CHAPTERS_AFTER <<<"$(db_counts)"
ARTWORKS=$("$ADB" shell "run-as $PKG sh -c 'ls files/Artworks 2>/dev/null | wc -l'" | tr -d '\r ')
echo "result: fatal=$FATAL alive=$ALIVE library_items ${ITEMS_BEFORE}→${ITEMS_AFTER} chapters ${CHAPTERS_BEFORE}→${CHAPTERS_AFTER} artworks=${ARTWORKS:-0}"
if [ "$FATAL" != "0" ]; then
  grep -A30 "FATAL EXCEPTION" "$DB_TMP/logcat.txt" \
    | grep -E "FATAL|Error|Exception|at com.tortugapower" | sed 's/^E\/AndroidRuntime([ 0-9]*): //' | head -12
fi
