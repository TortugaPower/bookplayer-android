#!/usr/bin/env bash
# Sentry ANDROID-BOOKPLAYER-13: zero-fill the playback_settings DataStore file and relaunch twice.
#
# Usage: scripts/chaos/corrupt-playback-settings.sh [package]
# Env:   ADB=/path/to/adb (default: adb on PATH)
#
# Before the fix: every launch dies with `CorruptionException: Unable to parse preferences proto`
# (`InvalidProtocolBufferException: Protocol message contained an invalid tag (zero)`).
# After the fix:  the app starts, logs `W/PlaybackSettings: playback_settings is corrupt; resetting to
#                 defaults`, and the file is rewritten with defaults.
set -euo pipefail

PKG=${1:-com.tortugapower.audiobookplayer}
ADB=${ADB:-adb}

"$ADB" shell am force-stop "$PKG"
# Quoting matters: the inner sh -c string must survive adb shell → run-as.
"$ADB" shell "run-as $PKG sh -c 'mkdir -p files/datastore; head -c 64 /dev/zero > files/datastore/playback_settings.preferences_pb; ls -l files/datastore/'"

for attempt in 1 2; do
  "$ADB" logcat -c
  "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 7
  FATAL=$("$ADB" logcat -d -v brief | grep -c "FATAL EXCEPTION" || true)
  ALIVE=$("$ADB" shell pidof "$PKG" | wc -w | tr -d ' ')
  echo "launch $attempt: fatal=$FATAL alive=$ALIVE"
  "$ADB" logcat -d -v brief | grep -E "CorruptionException|invalid tag|PlaybackSettings" | head -3
done
