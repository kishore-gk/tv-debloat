#!/bin/bash
# Undoes 05_make_plume_home.sh: Google TV's launcher is Home again (Plume's Home button service still covers it).
. "$(dirname "$0")/lib.sh"
connect
$A cmd role add-role-holder android.app.role.HOME com.google.android.apps.tv.launcherx 2>&1
$A cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity 2>&1
rm -f "$STATE/plume_is_home"
echo "Home now: $($A cmd role get-role-holders android.app.role.HOME | tr -d '\r')"
