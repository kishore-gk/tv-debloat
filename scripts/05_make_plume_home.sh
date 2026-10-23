#!/bin/bash
# Makes Plume the TV's real Home. Google TV's launcher stays installed and enabled (disabling it makes this TV
# boot-loop); it just stops being the protected "home" process, so Android frees its memory like any closed app.
# The TV then starts straight into Plume. Undo: scripts/05_restore_google_home.sh
#
# Can't brick the TV: it changes one setting (which app is Home). If Plume ever fails to start as Home:
#   1. scripts/05_restore_google_home.sh
#   2. or, with no adb: TV Settings > Apps > Plume > Uninstall (Google TV becomes Home again)
. "$(dirname "$0")/lib.sh"
connect
echo "Home before: $($A cmd role get-role-holders android.app.role.HOME | tr -d '\r')" | tee "$STATE/home_before.txt"
$A cmd role add-role-holder android.app.role.HOME dev.plume 2>&1
$A cmd package set-home-activity dev.plume/.MainActivity 2>&1
RESOLVED=$($A cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r')
echo "Home now:    $($A cmd role get-role-holders android.app.role.HOME | tr -d '\r')"
echo "Home opens:  $RESOLVED"
if echo "$RESOLVED" | grep -q "dev.plume"; then
  touch "$STATE/plume_is_home"   # 04_build_install.sh keeps it this way after reinstalls
  $A am kill com.google.android.apps.tv.launcherx   # only stops it if it's in the background
  echo "Done. Plume is the TV's Home. Check Home, the Settings button, your apps and casting, then restart the TV."
else
  echo "This TV didn't accept a different Home. Nothing else was changed."
fi
