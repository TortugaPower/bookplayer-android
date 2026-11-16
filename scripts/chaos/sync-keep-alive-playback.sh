#!/usr/bin/env bash
# Background listening must not spend the Android 15+ dataSync budget: sync work while playback holds the foreground
# never starts the sync host (TaskConcurrencyServiceHost). Only a transfer with the app off screen and playback out of
# the foreground does (SyncKeepAlivePolicy).
#
# Usage: scripts/chaos/sync-keep-alive-playback.sh [audio-file] [package]
# Env:   ADB=/path/to/adb (default: adb on PATH); the DEV build (run-as, sqlite3) on an API 31+ emulator.
#
# Imports the fixture, plays it, sends the app home (playback goes on in the foreground service), then queues sync
# work: a media-server progress push written straight into sync_tasks, and a network change so the engine rescans
# and picks it up (Room doesn't see another process's writes). Reads back whether the engine ran it, and whether the
# sync host ever came up: its service record, or ActivityManager's foreground-start line for it.
#   Before (BKPLY-453): a lane going busy started the host, allowed from the background while playback is a foreground
#     service. Playback's progress pushes every 10 s kept it up for hours: the budget went on background listening
#     (ANDROID-BOOKPLAYER-2F's field condition, and many -8 timeouts).
#   After (BKPLY-454): WORK RUN: yes, HOST STARTED: no.
set -euo pipefail

FILE=${1:-core/src/test/resources/chapterfixtures/m4b_WELLFORMED.m4b}
PKG=${2:-com.tortugapower.audiobookplayer}
ADB=${ADB:-adb}
HERE=$(cd "$(dirname "$0")" && pwd)
TITLE=$(basename "${FILE%.*}")

tap() {
  local tries=${2:-10} c
  for ((i = 0; i < tries; i++)); do
    "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    c=$("$ADB" shell cat /sdcard/ui.xml | tr '>' '\n' | grep -iE "(text|content-desc)=\"[^\"]*$1[^\"]*\"" \
      | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 \
      | awk -F'[][,]' '{print int(($2+$5)/2), int(($3+$6)/2)}') || true
    if [ -n "$c" ]; then "$ADB" shell input tap $c; sleep 2; return 0; fi
    sleep 1
  done
  echo "no node matching '$1'"; return 1
}
session_state() { "$ADB" shell dumpsys media_session | grep -A12 "$PKG/" | grep -oE 'state=PlaybackState \{state=[A-Z_]*\(?[0-9]+' | head -1 | grep -oE '[0-9]+$' || echo "?"; }
host_record() { "$ADB" shell dumpsys activity services "$PKG" | grep -c 'TaskConcurrencyServiceHost' || true; }
host_starts() { "$ADB" logcat -d -T "$1" 2>/dev/null | grep -c 'TaskConcurrencyServiceHost.onCreate\|Background started FGS.*TaskConcurrencyServiceHost' || true; }
rig_rows() { "$ADB" shell "run-as $PKG sqlite3 databases/bookplayer.db \"SELECT COUNT(*) FROM sync_tasks WHERE id LIKE 'rig-%'\"" 2>/dev/null | tr -d '\r' || true; }

"$ADB" shell am force-stop "$PKG"
"$ADB" shell pm clear "$PKG" >/dev/null
ADB="$ADB" "$HERE/import-file.sh" "$FILE" "$PKG" | tail -1
tap "$TITLE\." 20                       # open the book: the player opens and playback starts
sleep 3
"$ADB" shell input keyevent KEYCODE_HOME
sleep 3
SINCE=$("$ADB" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
echo "in the background: session state=$(session_state) (3 = playing)"

# A media-server progress push, the one job a signed-out device runs. Its empty payload names no server, so the
# engine just drops it: the row going is the engine having run it.
NOW=$(date +%s000)
"$ADB" shell "run-as $PKG sqlite3 databases/bookplayer.db \"INSERT INTO sync_tasks (id, taskID, queueKey, jobType, position, payload, status, createdAt, errorMessage, attempts) VALUES ('rig-push','rig','jellyfin','external_update',0,'{}','PENDING',$NOW,NULL,0)\""
"$ADB" shell svc wifi disable; sleep 4; "$ADB" shell svc wifi enable
sleep 20

LEFT=$(rig_rows); REC=$(host_record); STARTS=$(host_starts "$SINCE")
echo "still playing: session state=$(session_state)"
echo "WORK RUN: $([ "${LEFT:-1}" = 0 ] && echo yes || echo no)   (rig rows left: ${LEFT:-?})"
echo "HOST STARTED: $([ "$REC" -gt 0 ] || [ "$STARTS" -gt 0 ] && echo yes || echo no)   (service record: $REC, starts logged: $STARTS)"
"$ADB" shell am force-stop "$PKG"
