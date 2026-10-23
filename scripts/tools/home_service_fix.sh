#!/bin/bash
# Home lands on Google TV instead of Plume: reconnects Plume's Home button service and checks it.
# (Usual cause on TCL: the AUTO_START permission, which TCL resets on every install. 04_build_install.sh sets it.)
. "$(dirname "$0")/../lib.sh"
connect
SVC="dev.plume/dev.plume.HomeOverride"
$A appops set dev.plume AUTO_START allow 2>/dev/null
$A appops set dev.plume ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null
$A am force-stop dev.plume
$A settings delete secure enabled_accessibility_services >/dev/null
$A settings put secure accessibility_enabled 0
sleep 1
$A settings put secure enabled_accessibility_services "$SVC"
$A settings put secure accessibility_enabled 1
BOUND=0
for i in 1 2 3 4 5 6 7 8 9 10; do
  sleep 1
  if $A dumpsys accessibility | grep "Bound services" | grep -qi "plume"; then BOUND=1; break; fi
done
$A am start -n dev.plume/.MainActivity >/dev/null 2>&1
if [ "$BOUND" = 1 ]; then echo "Running. Press Home: it should land on Plume."; else
  echo "Still not running:"
  $A dumpsys accessibility | grep -E "Bound services|Binding services|Enabled services"
  echo "Permissions:"; $A appops get dev.plume | grep -iE "AUTO_START|RESTRICTED|USAGE"
fi
