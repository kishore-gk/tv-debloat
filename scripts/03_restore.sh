#!/bin/bash
# Undoes 03_debloat.sh: turns every package in debloat-packages.txt back on.
. "$(dirname "$0")/lib.sh"
connect
LOG="$REPORTS/restore-$(date +%Y%m%d-%H%M%S).txt"
grep -vE '^[[:space:]]*(#|$)' "$ROOT/scripts/debloat-packages.txt" | while read -r how pkg _; do
  case "$how" in
    disable) out=$($A pm enable "$pkg" </dev/null 2>&1) ;;
    remove)  out=$($A cmd package install-existing "$pkg" </dev/null 2>&1) ;;
    *) continue ;;
  esac
  echo "$pkg -> $(echo "$out" | tr -d '\r')" | tee -a "$LOG"
done
echo "Disabled now: $($A pm list packages -d | wc -l | tr -d ' '). Log: ${LOG#$ROOT/}"
