#!/bin/bash
# Plume logs when focus moves without a key press (and what moved it). This prints that log.
. "$(dirname "$0")/../lib.sh"
connect
adb -s "$TV" logcat -d -s PlumeFocus:W
