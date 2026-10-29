#!/usr/bin/env bash
# BKPLY-454 on a signed-in LITE/PRO device: a transfer keeps the process alive (TaskConcurrencyServiceHost) once
# nothing else does, and only then (SyncKeepAlivePolicy).
#
# Usage: scripts/chaos/sync-keep-alive-transfer.sh prepare|background|demotion [package]
# Env:   ADB=/path/to/adb (default: adb on PATH); MEDIA_TIMEOUT_MS (prepare, default 20000); WATCH_S (default 600).
#        The DEV build (the media timeout knob), signed in, with a cloud book that isn't downloaded yet; one big enough
#        that its download outlasts the wait (demotion: the media timeout plus a few seconds).
#        Never clears the app or touches its database: it runs on a real phone with a real library.
#        On Android 13+ the NOTIFICATION line is empty unless the app has the notification permission (asked the first
#        time a transfer runs with the app on screen): without it the service runs with its notice hidden.
#
#   prepare    — force-stops the app, shortens media3's paused-foreground window to MEDIA_TIMEOUT_MS (read when the
#                playback service is created) and opens the app. `demotion` needs it; `background` doesn't.
#   background — with a download started in the app (tap a cloud book's cover), sends the app home and watches.
#                Expect: HOST STARTED yes, allowed as the app leaves the screen; NOTIFICATION "Downloading books";
#                the host stopping itself about a minute after the download ends.
#   demotion   — after `prepare`: play a book, pause it, start a download in the app; this sends the app home and
#                watches. While the paused playback holds the foreground, the host must stay down (it would spend the
#                dataSync budget for nothing); when media3 demotes, the host must come up right then, while the app
#                may still start it (PlaybackForeground.onLeaving).
#                Expect: HOST WHILE PLAYBACK HELD THE FOREGROUND no; HOST STARTED AT THE DEMOTION yes.
# Clears the knob when it's done (`demotion`), or run `settings delete global bookplayer_media_fgs_timeout_ms`.
set -euo pipefail

MODE=${1:-}
PKG=${2:-com.tortugapower.audiobookplayer}
ADB=${ADB:-adb}
WATCH_S=${WATCH_S:-600}

# A service of ours: is it a foreground service right now. A demoted record prints no isForeground line, so the record
# is cut at the next ServiceRecord before looking (Android 16 appends ` c:<pkg>` inside the braces).
is_foreground() {
  "$ADB" shell dumpsys activity services "$PKG" | awk -v svc="$1" '
    /\* ServiceRecord\{/ { inrec = ($0 ~ svc) }
    inrec && /isForeground=/ { sub(/.*isForeground=/, ""); sub(/ .*/, ""); print; found = 1; exit }
    END { if (!found) print "false" }'
}
host_up() { [ "$(is_foreground TaskConcurrencyServiceHost)" = "true" ]; }
media_up() { [ "$(is_foreground AudioPlayerService)" = "true" ]; }
# The sync service's notice, found by its channel: playback's notification is the app's too
host_notice() {
  "$ADB" shell dumpsys notification --noredact 2>/dev/null | awk -v pkg="$PKG" '
    /NotificationRecord\(/ { inrec = ($0 ~ "pkg=" pkg) && ($0 ~ /channel=task_concurrency_channel /) }
    inrec && /android.title=/ { sub(/.*android.title=[A-Za-z]* \(/, ""); sub(/\)$/, ""); print; exit }'
}
since_now() { "$ADB" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r'; }
# ActivityManager's verdict on each foreground start from the background, and the app's own lines
host_verdict() { "$ADB" logcat -d -T "$1" 2>/dev/null | grep 'Background started FGS.*TaskConcurrencyServiceHost' | grep -o 'FGS: [A-Za-z]*.*code:[A-Z_]*' | head -1 || true; }
host_creates() { "$ADB" logcat -d -T "$1" 2>/dev/null | grep -c 'TaskConcurrencyServiceHost.onCreate' || true; }
downloads_done() { "$ADB" logcat -d -T "$1" 2>/dev/null | grep -c 'DownloadFileProcessor.*Download complete' || true; }
download_errors() { "$ADB" logcat -d -T "$1" 2>/dev/null | grep -c 'DownloadFileProcessor.*Exception during download' || true; }

# After the start: how long the host stays up, and whether the download finished meanwhile
watch_until_the_host_stops() {
  local since=$1 t=0
  while host_up && [ "$t" -lt "$WATCH_S" ]; do sleep 5; t=$((t + 5)); done
  echo "HOST STOPPED: $(host_up && echo "no (still up after ${WATCH_S} s)" || echo "yes, ~${t} s later")   downloads complete: $(downloads_done "$since"), failed: $(download_errors "$since")"
}

case "$MODE" in
  prepare)
    "$ADB" shell am force-stop "$PKG"
    "$ADB" shell settings put global bookplayer_media_fgs_timeout_ms "${MEDIA_TIMEOUT_MS:-20000}"
    "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    echo "app open, paused playback demotes after ${MEDIA_TIMEOUT_MS:-20000} ms: play, pause, start a download, then run demotion"
    ;;

  background)
    SINCE=$(since_now)
    "$ADB" shell input keyevent KEYCODE_HOME
    for ((i = 0; i < 8; i++)); do host_up && break; sleep 2; done
    echo "HOST STARTED: $(host_up && echo yes || echo no)   (onCreate: $(host_creates "$SINCE"), verdict: $(host_verdict "$SINCE"))"
    echo "NOTIFICATION: $(host_notice)"
    echo "PLAYBACK IN THE FOREGROUND: $(media_up && echo "yes (expected no: the host isn't needed then)" || echo no)"
    watch_until_the_host_stops "$SINCE"
    ;;

  demotion)
    trap '"$ADB" shell settings delete global bookplayer_media_fgs_timeout_ms >/dev/null 2>&1 || true' EXIT
    media_up || { echo "playback isn't in the foreground: play a book and pause it first"; exit 1; }
    SINCE=$(since_now)
    "$ADB" shell input keyevent KEYCODE_HOME
    held=no; t=0
    while media_up && [ "$t" -lt "$WATCH_S" ]; do host_up && held=yes; sleep 1; t=$((t + 1)); done
    echo "HOST WHILE PLAYBACK HELD THE FOREGROUND: $held   (playback demoted ~${t} s after leaving the app)"
    DEMOTED=$(since_now)
    for ((i = 0; i < 5; i++)); do host_up && break; sleep 2; done
    echo "HOST STARTED AT THE DEMOTION: $(host_up && echo yes || echo no)   (verdict: $(host_verdict "$SINCE"))"
    echo "NOTIFICATION: $(host_notice)"
    [ "$(downloads_done "$SINCE")" = 0 ] || echo "note: a download finished before the demotion (downloads complete: $(downloads_done "$SINCE")); use a bigger book"
    watch_until_the_host_stops "$DEMOTED"
    ;;

  *) echo "mode must be prepare, background or demotion"; exit 1 ;;
esac
