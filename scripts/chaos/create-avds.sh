#!/usr/bin/env bash
# Create the two emulators the crash-reproduction recipes assume (see docs/crash-repro.md):
#   bp-lowend-31  API 31 (Android 12), Pixel 3a profile, 2 GB RAM, 2 cores — the low-end phones that
#                 dominate the Sentry crash list; google_apis image so `adb root` works.
#   bp-api36      API 36 (Android 16), Pixel 7 profile — the dataSync FGS budget / timeout behaviour.
#
# Usage: scripts/chaos/create-avds.sh
# Env:   ANDROID_HOME (default: ~/Library/Android/sdk)
set -euo pipefail

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
ABI=arm64-v8a; [ "$(uname -m)" = "x86_64" ] && ABI=x86_64

create() { # name, api, device, ram, cores, data-partition, extra config lines...
  local name=$1 api=$2 device=$3 ram=$4 cores=$5 data=$6; shift 6
  local image="system-images;android-$api;google_apis;$ABI"
  [ -d "$SDK/system-images/android-$api/google_apis/$ABI" ] || yes 2>/dev/null | "$SDKMANAGER" --install "$image" >/dev/null
  echo no | "$AVDMANAGER" create avd -n "$name" -k "$image" -d "$device" --force >/dev/null 2>&1
  local ini="$HOME/.android/avd/$name.avd/config.ini"
  for kv in "hw.ramSize=$ram" "hw.cpu.ncore=$cores" "disk.dataPartition.size=$data" "hw.keyboard=yes" "hw.gpu.mode=auto" "$@"; do
    local key=${kv%%=*}
    grep -q "^$key=" "$ini" && sed -i.bak "s|^$key=.*|$kv|" "$ini" || echo "$kv" >> "$ini"
  done
  rm -f "$ini.bak"; echo "created $name ($image, $device, ${ram}MB RAM, $cores cores)"
}

create bp-lowend-31 31 pixel_3a 2048 2 6G vm.heapSize=256
create bp-api36     36 pixel_7  4096 4 8G

echo
echo "boot one with:  \$ANDROID_HOME/emulator/emulator -avd bp-lowend-31 -no-snapshot-load -no-boot-anim -no-audio"
