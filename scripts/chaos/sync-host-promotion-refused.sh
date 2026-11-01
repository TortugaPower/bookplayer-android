#!/usr/bin/env bash
# Sentry ANDROID-BOOKPLAYER-2F: the sync host is refused promotion once the Android 15+ dataSync budget is spent.
#
# Usage: scripts/chaos/sync-host-promotion-refused.sh [package]
# Env:   ADB=/path/to/adb (default: adb on PATH)
#
# Needs a debuggable build on an API 35+ google_apis emulator (bp-api36): the rig roots adbd to shorten the dataSync
# budget (device_config) and to start the non-exported service from the shell. With the app in the background it
# starts the sync host, lets the 10 s budget run out (onTimeout), then starts it again, and Android refuses the
# promotion ("Time limit already exhausted for foreground service type dataSync"). In the field the second start
# is the app's own, allowed from the background while playback holds a foreground service.
#   Before (1.2.0 – 1.3.0): onCreate catches the refusal and returns before building the engine; onDestroy then reads
#     the lateinit engine. FATAL UninitializedPropertyAccessException: the process dies, and playback with it.
#   After: the service builds nothing (the engine is built at process start, SyncRuntime). The refusal is logged,
#     the service stops, and the process lives on with its engine.
# The budget's 24 h bookkeeping survives until a reboot or an uninstall: reboot the emulator between runs.
set -euo pipefail

PKG=${1:-com.tortugapower.audiobookplayer}
ADB=${ADB:-adb}
SVC="$PKG/.logic.TaskConcurrencyServiceHost"

crashes() { "$ADB" shell dumpsys dropbox data_app_crash 2>/dev/null | grep -c "data_app_crash" || true; }

"$ADB" root >/dev/null
sleep 2
"$ADB" wait-for-device
"$ADB" shell device_config set_sync_disabled_for_tests persistent
"$ADB" shell device_config put activity_manager data_sync_fgs_timeout_duration 10000
trap '"$ADB" shell device_config delete activity_manager data_sync_fgs_timeout_duration >/dev/null 2>&1 || true
      "$ADB" shell device_config set_sync_disabled_for_tests none >/dev/null 2>&1 || true' EXIT

"$ADB" shell am force-stop "$PKG"
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8
# The budget burns only while the app isn't on screen
"$ADB" shell input keyevent KEYCODE_HOME
sleep 2
PID=$("$ADB" shell pidof "$PKG" | tr -d '\r')
CRASHES_BEFORE=$(crashes)
"$ADB" logcat -c

"$ADB" shell am start-foreground-service -n "$SVC" >/dev/null
echo "waiting for the 10 s dataSync budget to run out…"
for _ in $(seq 1 30); do
  "$ADB" logcat -d | grep -q "FGS (dataSync) timed out" && break
  sleep 1
done
sleep 2
"$ADB" shell am start-foreground-service -n "$SVC" >/dev/null
sleep 5

REFUSED=$("$ADB" logcat -d | grep -c "Time limit already exhausted" || true)
NOW_PID=$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
CRASHES_AFTER=$(crashes)
echo "BUDGET TIMED OUT: $("$ADB" logcat -d | grep -q "FGS (dataSync) timed out" && echo yes || echo no)"
echo "PROMOTION REFUSED: $([ "$REFUSED" -gt 0 ] && echo yes || echo no)"
echo "PROCESS: $PID → ${NOW_PID:-gone}"
echo "NEW APP CRASHES: $((CRASHES_AFTER - CRASHES_BEFORE))"
"$ADB" logcat -d | grep -E "UninitializedPropertyAccessException|FATAL EXCEPTION" | head -3 || true
