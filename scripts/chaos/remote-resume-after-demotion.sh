#!/usr/bin/env bash
# Sentry ANDROID-BOOKPLAYER-21: resume playback from the background with no user gesture, after media3
# has dropped the foreground service.
#
# Usage: scripts/chaos/remote-resume-after-demotion.sh [direct|mediakey|shellkey|cold|coldmediakey|dead] [audio-file] [package]
# Env:   ADB=/path/to/adb (default: adb on PATH)
#
# Needs the DEV build (the debug receiver and the timeout knob exist only in the dev flavor) on an
# API 31+ emulator (measured on bp-lowend-31 / API 31 and bp-api36 / Android 16, same verdicts). Imports the fixture, plays it, pauses through the system (a media key), sends the
# app to the background, waits past media3's foreground timeout (shortened here from 10 min to 15 s)
# AND until the sync host has idle-stopped (while any service of ours is foreground, every foreground
# start is allowed — the UID is already FGS — which is why the field failure needs a quiet app), then
# resumes:
#   direct   — the app calls its own player, as the watch's remote play and Android Auto's commands do.
#   mediakey — the app dispatches KEYCODE_MEDIA_PLAY to the system itself (AudioManager.dispatchMediaKeyEvent).
#   shellkey — KEYCODE_MEDIA_PLAY injected like a hardware key (headset / Bluetooth path).
#   cold / coldmediakey / dead — the paused app is killed first (`am kill` / `am force-stop`).
# Reads back: is audio playing, is the service foreground again, is the media notification up, and
# ActivityManager's own verdict line for the foreground start.
# Measured on API 31 (1.2.0 code, media3 1.11):
#   direct   → PLAYING yes, FOREGROUND false, NOTIFICATION no, "Background started FGS: Disallowed (DENIED)"
#              — ANDROID-BOOKPLAYER-21's condition: media3 catches the exception (1.7 crashed here), the
#              controls are gone and playback runs in a plain background service until the OS reclaims it.
#              With the fix, REPORTED BY APP: yes — ForegroundStartRefusals records it (a handled Sentry
#              event in prod); the next gesture (headset press, opening the app) restores the notification.
#   mediakey → DENIED as well: the system will not allowlist a package for a media key it sent itself.
#   shellkey → Allowed (TEMP_ALLOWED_WHILE_IN_USE): MediaSessionService allowlists the target when the
#              key comes from somewhere else (here the launcher, as with a headset). This is the one
#              gesture-less path Android grants, and an app cannot take it on its own behalf.
set -euo pipefail

MODE=${1:-direct}
FILE=${2:-core/src/test/resources/chapterfixtures/m4b_WELLFORMED.m4b}
PKG=${3:-com.tortugapower.audiobookplayer}
ADB=${ADB:-adb}
HERE=$(cd "$(dirname "$0")" && pwd)
TIMEOUT_MS=15000
KILL=no

case "$MODE" in
  direct)      ACTION="$PKG.debug.RESUME" ;;
  mediakey)    ACTION="$PKG.debug.RESUME_VIA_MEDIA_KEY" ;;
  cold)        ACTION="$PKG.debug.RESUME"; KILL=yes ;;   # the OS reclaimed the paused app; the watch says play
  coldmediakey) ACTION="$PKG.debug.RESUME_VIA_MEDIA_KEY"; KILL=yes ;;
  dead)        ACTION="$PKG.debug.RESUME"; KILL=force ;;  # app force-stopped: the resume itself creates process and service
  shellkey)    ACTION="" ;;                                # KEYCODE_MEDIA_PLAY injected as a hardware key (the headset path)
  *) echo "mode must be direct, mediakey, cold, coldmediakey, dead or shellkey"; exit 1 ;;
esac

# Taps the centre of the first node whose text or content-desc matches the regex; retries for a while.
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
# Playback state of our media session: 3 = playing, 2 = paused, 1 = stopped. API 31 prints `state=3`,
# Android 16 prints `state=PLAYING(3)` — the number is read either way.
session_state() { "$ADB" shell dumpsys media_session | grep -A12 "$PKG/" | grep -oE 'state=PlaybackState \{state=[A-Z_]*\(?[0-9]+' | head -1 | grep -oE '[0-9]+$' || echo "?"; }
# The playback service's foreground flag, from the ActivityManager's own record. A demoted record prints
# no isForeground line at all, so the record is cut at the next ServiceRecord before looking.
# (Android 16 appends ` c:<pkg>` inside the record's braces, so the class name is matched on its own.)
is_foreground() {
  "$ADB" shell dumpsys activity services "$PKG" | awk '
    /\* ServiceRecord\{/ { inrec = ($0 ~ /\.service\.AudioPlayerService/) }
    inrec && /isForeground=/ { sub(/.*isForeground=/, ""); sub(/ .*/, ""); print; found = 1; exit }
    END { if (!found) print "false" }'
}
# Every service of ours that is currently a foreground service (the sync host shows up here while it runs).
foreground_services() { "$ADB" shell dumpsys activity services "$PKG" | awk '/\* ServiceRecord\{/ {n=$0; sub(/.*\//,"",n); sub(/[ }].*/,"",n)} /isForeground=true/ {printf "%s ", n}'; }
media_notification() { "$ADB" shell dumpsys notification --noredact 2>/dev/null | grep -c "pkg=$PKG" || true; }

"$ADB" shell settings put global bookplayer_media_fgs_timeout_ms "$TIMEOUT_MS"
trap '"$ADB" shell settings delete global bookplayer_media_fgs_timeout_ms >/dev/null 2>&1 || true' EXIT

TITLE=$(basename "${FILE%.*}")
"$ADB" shell am force-stop "$PKG"
"$ADB" shell pm clear "$PKG" >/dev/null
ADB="$ADB" "$HERE/import-file.sh" "$FILE" "$PKG" | tail -1
tap "$TITLE\." 20                       # open the book: the player opens and playback starts
sleep 3
echo "after play:   session state=$(session_state) foreground=$(is_foreground)"

"$ADB" shell input keyevent KEYCODE_MEDIA_PAUSE   # pause through the system, as a headset does
sleep 2
"$ADB" shell input keyevent KEYCODE_HOME          # background the app
echo "after pause:  session state=$(session_state) foreground=$(is_foreground)"

# Past media3's (shortened) engaged timeout AND until nothing of ours is a foreground service any more:
# playback enqueues sync work, the sync host promotes itself for it and idle-stops ~60 s after its queues
# empty — while it is up, any foreground start of ours is allowed (the UID is already FGS), which is why
# the field failure needs a quiet app.
for ((i = 0; i < 40; i++)); do
  sleep 5
  [ "$(is_foreground)" = "false" ] && [ -z "$(foreground_services)" ] && break
done
DEMOTED=$([ "$(is_foreground)" = "false" ] && echo yes || echo no)
echo "after wait:   session state=$(session_state) foreground=$(is_foreground) uid-fgs=[$(foreground_services)]  → DEMOTED: $DEMOTED (after $((i*5+5)) s)"
BCAST_FLAGS=""
if [ "$KILL" = yes ]; then
  "$ADB" shell am kill "$PKG"                      # only kills a background process — what the OS does under pressure
  sleep 2
elif [ "$KILL" = force ]; then
  "$ADB" shell am force-stop "$PKG"                # a stopped package needs the flag to receive the broadcast
  BCAST_FLAGS="--include-stopped-packages"
  sleep 2
fi
[ "$KILL" = no ] || echo "after kill:   process=$( ("$ADB" shell pidof "$PKG" 2>/dev/null || true) | wc -w | tr -d ' ' | sed 's/^0$/gone/; s/^[1-9].*/still alive/')"

SINCE=$("$ADB" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')   # quoted for the device shell; logcat is read from here on
if [ -n "$ACTION" ]; then
  "$ADB" shell am broadcast $BCAST_FLAGS -a "$ACTION" -n "$PKG/.debug.DebugPlaybackReceiver" >/dev/null
else
  "$ADB" shell input keyevent KEYCODE_MEDIA_PLAY   # injected like a hardware key: the system dispatches it to our session
fi
sleep 6
STATE=$(session_state); FG=$(is_foreground); NOTI=$(media_notification)
# ActivityManager logs every foreground start attempted from the background with its verdict and reason.
FGS_VERDICT=$("$ADB" logcat -d -T "$SINCE" 2>/dev/null | grep -o 'Background started FGS: [A-Za-z]*.*code:[A-Z_]*' | head -1 | sed 's/ \[callingPackage.*code:/ (/; s/$/)/' || true)
REFUSAL=$("$ADB" logcat -d -T "$SINCE" 2>/dev/null | grep -c 'Background started FGS: Denied\|mAllowStartForeground false\|ForegroundServiceStartNotAllowed' || true)
REPORTED=$("$ADB" logcat -d -T "$SINCE" 2>/dev/null | grep -c 'ForegroundStartRefusals: foreground start refused' || true)
ALIVE=$( ("$ADB" shell pidof "$PKG" 2>/dev/null || true) | wc -w | tr -d ' ')

echo
echo "MODE: $MODE"
echo "PLAYING: $([ "$STATE" = "3" ] && echo yes || echo "no (state=$STATE)")"
echo "FOREGROUND: $FG"
echo "NOTIFICATION: $([ "$NOTI" -gt 0 ] && echo yes || echo no)"
echo "FGS START: ${FGS_VERDICT:-not attempted}"
echo "REFUSAL LOGGED: $([ "$REFUSAL" -gt 0 ] && echo yes || echo no)"
echo "REPORTED BY APP: $([ "$REPORTED" -gt 0 ] && echo yes || echo no)   (ForegroundStartRefusals → handled Sentry event in prod)"
echo "PROCESS: $([ "$ALIVE" -gt 0 ] && echo alive || echo gone)"
