#!/bin/bash
# Frame rate while you use Plume: move around the home screen for 20 seconds when asked.
. "$(dirname "$0")/../lib.sh"
connect
OUT="$REPORTS/framerate-$(date +%Y%m%d-%H%M%S).txt"
$A dumpsys gfxinfo dev.plume reset >/dev/null
echo "Now move between the dock and Continue Watching, and along the apps, for 20 seconds..."
for i in $(seq 20 -1 1); do printf "\r%2d " "$i"; sleep 1; done; echo
$A dumpsys gfxinfo dev.plume > "$OUT"
grep -E "Total frames|Janky|50th percentile|90th percentile" "$OUT"
echo "Saved to ${OUT#$ROOT/}"
