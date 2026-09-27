#!/usr/bin/env bash
# Sentry ANDROID-BOOKPLAYER-E / -F: "Broadcast already finished" on every launch with a widget placed.
#
# Usage: scripts/chaos/widget-self-broadcast.sh [package]
# Env:   ADB=/path/to/adb (default: adb on PATH)
#
# Needs a BookPlayer widget on the launcher's home screen (long-press the home screen → Widgets →
# BookPlayer → drag the preview out): the trigger only fires when AppWidgetManager reports a placed
# instance. Cold-starts the app and counts, in the system's broadcast history, the APPWIDGET_UPDATE
# broadcasts the app sent to its own AudioWidgetLargeProvider, then checks the widget was still
# re-rendered (the RemoteViews object the launcher holds changed).
# Before the fix: SELF-BROADCASTS: 1 per launch — the goAsync() PendingResult that vivo's Funtouch 13
#                 finishes twice. A stock emulator never double-finishes, so it does not crash here.
# After the fix:  SELF-BROADCASTS: 0 and RENDERED: yes — playback's first emission rebuilt the widget
#                 in-process, without a receiver. SYSTEM-BROADCASTS counts the updates android itself
#                 sent in the same window (placement, restore, package update); those still reach the
#                 receiver and its goAsync(), so a render with both counts at 0 is the in-process path.
set -euo pipefail

PKG=${1:-com.tortugapower.audiobookplayer}
ADB=${ADB:-adb}

widgets() { "$ADB" shell dumpsys appwidget | sed -n '/^Widgets:/,/^Hosts:/p'; }
placed=$(widgets | grep -c AudioWidgetLargeProvider || true)
[ "$placed" -gt 0 ] || { echo "no BookPlayer widget on the launcher — place one first"; exit 1; }

# The RemoteViews identity the launcher holds for the first placed widget; a rebuild replaces it.
views_hash() { widgets | grep -o 'views=[^ ]*' | head -1; }
# One caller per APPWIDGET_UPDATE record. The dump strips an app-sent intent's component (the app has a
# single provider anyway), so the caller line is what tells the app's own broadcasts from android's.
update_callers() {
  "$ADB" shell dumpsys activity broadcasts history \
    | grep -A3 'BroadcastRecord{.* android.appwidget.action.APPWIDGET_UPDATE}' | grep -o 'caller=[^ ]*' || true
}
self_broadcasts()   { update_callers | grep -c "caller=$PKG\$" || true; }
system_broadcasts() { update_callers | grep -c 'caller=android$' || true; }

"$ADB" shell am force-stop "$PKG"
sleep 2
self_before=$(self_broadcasts); sys_before=$(system_broadcasts); hash_before=$(views_hash)
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8
self_after=$(self_broadcasts); sys_after=$(system_broadcasts); hash_after=$(views_hash)
alive=$("$ADB" shell pidof "$PKG" 2>/dev/null || true)

echo "SELF-BROADCASTS: $((self_after - self_before))   (APPWIDGET_UPDATE sent by $PKG to its own provider during this launch)"
echo "SYSTEM-BROADCASTS: $((sys_after - sys_before))   (APPWIDGET_UPDATE sent by android in the same window)"
if [ "$hash_before" != "$hash_after" ]; then
  echo "RENDERED: yes   ($hash_before -> $hash_after)"
else
  echo "RENDERED: no    ($hash_after unchanged)"
fi
echo "PROCESS: $([ -n "$alive" ] && echo "alive (pid $alive)" || echo gone)"
