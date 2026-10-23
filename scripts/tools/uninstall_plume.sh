#!/bin/bash
# Removes Plume. Google TV's home screen takes over again by itself.
. "$(dirname "$0")/../lib.sh"
connect
adb -s "$TV" uninstall dev.plume
rm -f "$STATE/plume_is_home"
