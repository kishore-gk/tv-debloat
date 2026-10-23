#!/bin/bash
# When something odd happens at start-up (the wrong app opens, focus jumps): run it straight after.
# Saves crashes, which app started which (and from where), key presses and Plume's focus log.
. "$(dirname "$0")/../lib.sh"
connect
OUT="$REPORTS/boot-diag-$(date +%Y%m%d-%H%M%S).txt"
{
  echo "== uptime"; $A uptime
  echo "== crashes"; $A logcat -d -b crash | tail -60
  echo "== in front now"; $A dumpsys activity activities | grep -E "topResumedActivity|ResumedActivity" | head
  echo "== app ids (to read 'from uid' below)"; $A cmd package list packages -U | grep -iE "netflix|dev\.plume|launcherx|com\.tcl\.(tv|guard|initsetup|systemserver)"
  echo "== activity starts: who asked, from which uid"
  $A logcat -d | grep -E "ActivityTaskManager: (START|Background activity)" | tail -120
  echo "== which activity was resumed"
  $A logcat -d -b events | grep -iE "wm_create_activity|wm_set_resumed_activity" | tail -80
  echo "== Plume's focus log"; $A logcat -d -s PlumeFocus:W | tail -80
} > "$OUT" 2>&1
echo "Saved to ${OUT#$ROOT/}"
