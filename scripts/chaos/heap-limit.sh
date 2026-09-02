#!/usr/bin/env bash
# Shrink the per-app heap growth limit on a rootable emulator (google_apis images) so a large single
# allocation fails the way it does on a real device whose heap is already nearly full.
#
# Usage: scripts/chaos/heap-limit.sh <limit|reset>     e.g. 96m, 64m
# Env:   ADB=/path/to/adb (default: adb on PATH)
#
# The property is read by the zygote at startup, so the framework is restarted (≈20 s). Properties
# set this way are volatile: `reset` simply reboots the emulator.
set -euo pipefail

LIMIT=${1:?usage: heap-limit.sh <limit|reset>}
ADB=${ADB:-adb}

wait_boot() {
  "$ADB" wait-for-device
  for _ in $(seq 1 90); do
    [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { sleep 5; return 0; }
    sleep 2
  done
  echo "device did not finish booting"; return 1
}

if [ "$LIMIT" = "reset" ]; then
  "$ADB" reboot; wait_boot
else
  "$ADB" root >/dev/null 2>&1 || { echo "adb root refused: use a google_apis (non-Play) system image"; exit 1; }
  sleep 3; "$ADB" wait-for-device
  "$ADB" shell setprop dalvik.vm.heapgrowthlimit "$LIMIT"
  # stop/start does not clear sys.boot_completed; clear it ourselves so wait_boot really waits for
  # system_server to come back (it sets the property to 1 again at the end of its boot).
  "$ADB" shell setprop sys.boot_completed 0
  "$ADB" shell stop; sleep 2; "$ADB" shell start
  wait_boot
fi
echo "dalvik.vm.heapgrowthlimit = $("$ADB" shell getprop dalvik.vm.heapgrowthlimit)"
