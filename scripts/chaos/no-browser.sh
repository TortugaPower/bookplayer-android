#!/usr/bin/env bash
# Sentry ANDROID-BOOKPLAYER-1P / -1R: tap a link on a device with nothing that can open https.
#
# Usage: scripts/chaos/no-browser.sh [package]
# Env:   ADB=/path/to/adb (default: adb on PATH)
#
# Disables Chrome for the current user (the only https handler on a google_apis image), launches the
# app, walks to Settings → "View project on GitHub" with uiautomator, taps it, and reports.
# Before the fix: `FATAL EXCEPTION … IllegalArgumentException: Can't open https://github.com/…` caused by
#                 `ActivityNotFoundException`, and the process is gone.
# After the fix:  the process is alive and NotificationService logs a toast from the package (toast windows are
#                 not in the accessibility dump, so logcat is what proves the message was shown).
# Re-enable Chrome afterwards: adb shell pm enable com.android.chrome
set -euo pipefail

PKG=${1:-com.tortugapower.audiobookplayer}
ADB=${ADB:-adb}

# Taps the centre of the first node whose text or content-desc matches the regex.
tap() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local c
  c=$("$ADB" shell cat /sdcard/ui.xml | tr '>' '\n' | grep -iE "(text|content-desc)=\"[^\"]*$1[^\"]*\"" \
    | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 \
    | awk -F'[][,]' '{print int(($2+$5)/2), int(($3+$6)/2)}')
  [ -n "$c" ] || { echo "no node matching '$1'"; return 1; }
  "$ADB" shell input tap $c
  sleep 2
}

"$ADB" shell pm disable-user --user 0 com.android.chrome >/dev/null 2>&1 || true   # absent or already disabled is fine
HANDLERS=$("$ADB" shell pm query-activities -a android.intent.action.VIEW -d https://github.com | grep -c packageName= || true)
echo "https handlers after disabling Chrome: $HANDLERS"

"$ADB" shell am force-stop "$PKG"
"$ADB" logcat -c
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 6
# The bottom navigation's third tab is Settings (its items carry no text in the accessibility dump).
"$ADB" shell input tap 900 2000; sleep 2
for _ in 1 2 3; do "$ADB" shell input swipe 540 1500 540 500 300; sleep 1; done
tap "View project on GitHub"
sleep 3

FATAL=$("$ADB" logcat -d -v brief | grep -c "FATAL EXCEPTION" || true)
ALIVE=$("$ADB" shell pidof "$PKG" 2>/dev/null | wc -w | tr -d ' ' || true)   # pidof exits non-zero when the process is gone
TOAST=$("$ADB" logcat -d -v brief | grep -c "NotificationService.*Toast.*pkg=$PKG" || true)
echo "fatal=$FATAL alive=$ALIVE toast=$TOAST"
"$ADB" logcat -d -v brief | grep -E "Can't open|ActivityNotFoundException" | head -2
