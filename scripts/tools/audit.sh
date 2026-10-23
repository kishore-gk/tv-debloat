#!/bin/bash
# Read-only snapshot of what's running and what it costs. Leave the TV on Plume, untouched, for 2 minutes first.
# Saved to _local/reports/audit-<date>.txt. Compare two runs to see what a change freed.
. "$(dirname "$0")/../lib.sh"
connect
OUT="$REPORTS/audit-$(date +%Y%m%d-%H%M%S).txt"
{
  echo "== uptime / load"; $A uptime; $A cat /proc/loadavg; $A cat /proc/pressure/cpu 2>/dev/null; $A cat /proc/pressure/memory 2>/dev/null
  echo "== CPU: two samples 5 s apart (the second one counts)"
  $A top -b -n 2 -d 5 -m 35
  echo "== threads waiting in the kernel (D state). The load average counts these even when the CPU is idle."
  $A "ps -A -T -o STAT,TID,PID,NAME,CMD 2>/dev/null | grep -E '^ *D' | head -60"
  echo "== memory"; $A cat /proc/meminfo | head -8
  $A dumpsys meminfo -s 2>/dev/null | sed -n '1,90p'
  echo "== process states (TOP, foreground services, cached)"
  $A dumpsys activity lru | head -120
  echo "== running services by package"
  $A dumpsys activity services | grep -oE "ServiceRecord\{[^ ]+ u0 [^/ ]+" | awk '{print $3}' | sort | uniq -c | sort -rn | head -60
  echo "== Google TV's launcher"
  $A pidof com.google.android.apps.tv.launcherx
  $A dumpsys activity processes com.google.android.apps.tv.launcherx | grep -E "ProcessRecord|curProcState|lastActivityTime" | head -12
  echo "== Home app"; $A cmd role get-role-holders android.app.role.HOME
  $A cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
  echo "== disabled packages"; $A pm list packages -d | sort
} > "$OUT" 2>&1
echo "Free memory: $(grep MemAvailable "$OUT" | awk '{print int($2/1024)" MB"}'). Report: ${OUT#$ROOT/}"
