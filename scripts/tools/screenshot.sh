#!/bin/bash
# Screenshot of the TV: _local/shots/<name>.png
. "$(dirname "$0")/../lib.sh"
connect
mkdir -p "$ROOT/_local/shots"
NAME="${1:-shot-$(date +%H%M%S)}"
adb -s "$TV" exec-out screencap -p > "$ROOT/_local/shots/$NAME.png" && echo "Saved _local/shots/$NAME.png"
