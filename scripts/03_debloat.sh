#!/bin/bash
# Turns off the ads, promos and demo apps in scripts/debloat-packages.txt. Undo: scripts/03_restore.sh
# Never touches: your streaming apps, casting (Chromecast), AirPlay, Miracast, Dolby audio, picture
# processing, HDMI inputs, Google TV's launcher (disabling it makes this TV boot-loop).
. "$(dirname "$0")/lib.sh"
connect
LOG="$REPORTS/debloat-$(date +%Y%m%d-%H%M%S).txt"
grep -vE '^[[:space:]]*(#|$)' "$ROOT/scripts/debloat-packages.txt" | while read -r how pkg _; do
  case "$how" in
    disable) out=$($A pm disable-user --user 0 "$pkg" </dev/null 2>&1) ;;
    remove)  out=$($A pm uninstall --user 0 "$pkg" </dev/null 2>&1) ;;
    *) continue ;;
  esac
  echo "$how $pkg -> $(echo "$out" | tr -d '\r')" | tee -a "$LOG"
done
echo "Disabled now: $($A pm list packages -d | wc -l | tr -d ' '). Log: ${LOG#$ROOT/}"
