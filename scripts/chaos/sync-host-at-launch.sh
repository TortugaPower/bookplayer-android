#!/usr/bin/env bash
# Sync at launch: the sync host must not start for an empty queue, and work left over from an earlier session
# must run.
#
# Usage: scripts/chaos/sync-host-at-launch.sh [package]
# Env:   ADB=/path/to/adb (default: adb on PATH); a debuggable build (run-as) on an image with sqlite3.
#
# Three launches from a cleared app: (1) nothing queued (every logged-out user) — the host must NOT
# come up; (2) one leftover PENDING task inserted straight into sync_tasks — the host MUST come up;
# (3) one leftover RUNNING task (a killed session) — the host MUST come up for that too. Reads the ActivityManager service records and its foreground-start verdicts.
# Before the launch gate: the host promoted to a dataSync foreground service on every launch and sat
#                  "Idle" for a minute (a minute of the Android 15 dataSync budget for nothing).
# Since: launch 1 → HOST STARTED: no; launches 2 and 3 → HOST STARTED: yes.
# Since BKPLY-453 the engine starts at every process start (SyncRuntime) and runs leftover work itself; the host
#                  only keeps the process alive while lanes work (SyncKeepAlive), so a task that's done in a moment
#                  (this one: its empty payload names no server) may not bring it up at all. Launch 1 → HOST STARTED:
#                  no; launches 2 and 3 → LEFTOVER RUN: yes (the engine took the row), HOST STARTED either way.
# Since BKPLY-454 the host comes up only for a transfer with the app off screen (SyncKeepAlivePolicy), and a launch
#                  puts the app on screen: every launch → HOST STARTED: no; launches 2 and 3 → LEFTOVER RUN: yes.
set -euo pipefail

PKG=${1:-com.tortugapower.audiobookplayer}
ADB=${ADB:-adb}

host_record() { "$ADB" shell dumpsys activity services "$PKG" | grep -c 'TaskConcurrencyServiceHost' || true; }
host_verdicts() { "$ADB" logcat -d -T "$1" 2>/dev/null | grep -c 'Background started FGS.*TaskConcurrencyServiceHost' || true; }

launch_and_read() {  # $1 = label
  local since; since=$("$ADB" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
  "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 12
  local rec; rec=$(host_record); local verd; verd=$(host_verdicts "$since")
  local left; left=$("$ADB" shell "run-as $PKG sqlite3 databases/bookplayer.db \"SELECT COUNT(*) FROM sync_tasks WHERE id LIKE 'rig-%'\"" 2>/dev/null | tr -d '\r' || true)
  echo "$1 → HOST STARTED: $([ "$rec" -gt 0 ] || [ "$verd" -gt 0 ] && echo yes || echo no)   LEFTOVER RUN: $([ "${left:-0}" = 0 ] && echo yes || echo no)   (service record: $rec, foreground-start verdicts: $verd, rig rows left: ${left:-0})"
  "$ADB" shell am force-stop "$PKG"
  sleep 3   # let the teardown finish: a launch right on the heels of a force-stop can miss the seeded row
}

"$ADB" shell am force-stop "$PKG"; "$ADB" shell pm clear "$PKG" >/dev/null
launch_and_read "clean queue (logged-out user)"
# One leftover task, as a killed session would leave it: a media-server progress push, the one job a
# signed-out device runs (any other is held by the tier, so it starts nothing). Its empty payload names no
# server, so the engine just drops it: the row going is the engine having run it.
NOW=$(date +%s000)
"$ADB" shell "run-as $PKG sqlite3 databases/bookplayer.db \"INSERT INTO sync_tasks (id, taskID, queueKey, jobType, position, payload, status, createdAt, errorMessage, attempts) VALUES ('rig-leftover','rig','jellyfin','external_update',0,'{}','PENDING',$NOW,NULL,0)\""
echo "inserted leftover task: $("$ADB" shell "run-as $PKG sqlite3 databases/bookplayer.db \"SELECT COUNT(*) FROM sync_tasks WHERE status='PENDING'\"" | tr -d '\r') pending"
launch_and_read "one leftover PENDING task"
# A task a killed process left RUNNING: it counts as leftover work too (the engine resets it to PENDING
# when it starts), so the host must come up for it as well. The table is emptied rather than the app
# cleared: `pm clear` removes the database file, and sqlite3 would then create an empty one without it.
"$ADB" shell "run-as $PKG sqlite3 databases/bookplayer.db \"DELETE FROM sync_tasks\""
"$ADB" shell "run-as $PKG sqlite3 databases/bookplayer.db \"INSERT INTO sync_tasks (id, taskID, queueKey, jobType, position, payload, status, createdAt, errorMessage, attempts) VALUES ('rig-running','rig','jellyfin','external_update',0,'{}','RUNNING',$NOW,NULL,1)\""
echo "inserted leftover task: $("$ADB" shell "run-as $PKG sqlite3 databases/bookplayer.db \"SELECT COUNT(*) FROM sync_tasks WHERE status='RUNNING'\"" | tr -d '\r') running"
launch_and_read "one leftover RUNNING task (killed session)"
"$ADB" shell pm clear "$PKG" >/dev/null
