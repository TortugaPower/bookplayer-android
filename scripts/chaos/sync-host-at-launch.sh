#!/usr/bin/env bash
# Sync host at launch: it must start only for work left over from an earlier session.
#
# Usage: scripts/chaos/sync-host-at-launch.sh [package]
# Env:   ADB=/path/to/adb (default: adb on PATH); a debuggable build (run-as) on an image with sqlite3.
#
# Two launches from a cleared app: (1) nothing queued (every logged-out user) — the host must NOT come
# up; (2) one leftover PENDING task inserted straight into sync_tasks — the host MUST come up (and then
# idle-stop as usual). Reads the ActivityManager service records and its foreground-start verdicts.
# Before the gate: the host promoted to a dataSync foreground service on every launch and sat "Idle"
#                  for a minute (a minute of the Android 15 dataSync budget for nothing).
# After the gate:  launch 1 → HOST STARTED: no; launch 2 → HOST STARTED: yes.
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
  echo "$1 → HOST STARTED: $([ "$rec" -gt 0 ] || [ "$verd" -gt 0 ] && echo yes || echo no)   (service record: $rec, foreground-start verdicts: $verd)"
  "$ADB" shell am force-stop "$PKG"
}

"$ADB" shell am force-stop "$PKG"; "$ADB" shell pm clear "$PKG" >/dev/null
launch_and_read "clean queue (logged-out user)"
# One leftover task, as a killed session would leave it. A job type no processor handles: the host
# still has to come up to look at it, which is all this checks.
NOW=$(date +%s000)
"$ADB" shell "run-as $PKG sqlite3 databases/bookplayer.db \"INSERT INTO sync_tasks (id, taskID, queueKey, jobType, position, payload, status, createdAt, errorMessage, attempts) VALUES ('rig-leftover','rig','rig','rig_noop',0,'{}','PENDING',$NOW,NULL,0)\""
echo "inserted leftover task: $("$ADB" shell "run-as $PKG sqlite3 databases/bookplayer.db \"SELECT COUNT(*) FROM sync_tasks WHERE status='PENDING'\"" | tr -d '\r') pending"
launch_and_read "one leftover PENDING task"
"$ADB" shell pm clear "$PKG" >/dev/null
