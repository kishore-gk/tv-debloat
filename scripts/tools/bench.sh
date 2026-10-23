#!/bin/bash
# Repeatable frame-rate benchmark: presses the same remote keys over adb every run, then reads Plume's frame stats.
. "$(dirname "$0")/../lib.sh"
connect
OUT="$REPORTS/bench-$(date +%Y%m%d-%H%M%S).txt"
key() { $A input keyevent "$1"; sleep 0.45; }
$A am start -n dev.plume/.MainActivity >/dev/null 2>&1   # an adb Home press can land on Google TV
sleep 3
$A dumpsys activity activities | grep -E "topResumedActivity" | grep -q "dev.plume" || { echo "Plume isn't in front; stopping."; exit 1; }
$A dumpsys gfxinfo dev.plume reset >/dev/null
START=$(date +%s)
for cycle in 1 2 3 4 5 6; do
  key KEYCODE_DPAD_UP; key KEYCODE_DPAD_RIGHT; key KEYCODE_DPAD_RIGHT; key KEYCODE_DPAD_LEFT
  key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_RIGHT; key KEYCODE_DPAD_RIGHT; key KEYCODE_DPAD_LEFT; key KEYCODE_DPAD_LEFT
done
$A dumpsys gfxinfo dev.plume > "$OUT"
grep -E "Total frames|^Janky frames:|90th percentile" "$OUT"
echo "Seconds: $(( $(date +%s) - START )). Saved to ${OUT#$ROOT/}"
