#!/bin/bash
# Read-only: records what's on the TV before you change anything (packages, versions, what's disabled,
# the device's properties). Saved to _local/reports/inventory-<date>/. Changes nothing.
. "$(dirname "$0")/lib.sh"
connect
D="$REPORTS/inventory-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$D"
$A pm list packages > "$D/packages_all.txt"
$A pm list packages -s > "$D/packages_system.txt"
$A pm list packages -3 > "$D/packages_user.txt"
$A pm list packages -d > "$D/packages_disabled.txt"
$A dumpsys package packages | grep -E "Package \[|versionName" > "$D/package_versions.txt"
$A getprop > "$D/props.txt"
$A cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$D/home_app.txt"
echo "Model: $($A getprop ro.product.model | tr -d '\r')  Android: $($A getprop ro.build.version.release | tr -d '\r')"
echo "Packages: $(wc -l < "$D/packages_all.txt") ($(wc -l < "$D/packages_user.txt") installed by you or the shop, $(wc -l < "$D/packages_disabled.txt") disabled)"
echo "Saved to ${D#$ROOT/}"
