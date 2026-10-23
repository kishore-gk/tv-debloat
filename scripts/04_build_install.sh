#!/bin/bash
# Builds the launcher (launcher/) and installs it on the TV with everything it needs. Run again after any change.
#   - grants: changing secure settings (to keep its Home button service on), reading Continue Watching
#   - allows: reading which app is in front, and TCL's AUTO_START (TCL resets it on every install; without it
#     Android never starts the Home button service)
#   - switches on Plume's Home button service and checks it's running
set -e
. "$(dirname "$0")/lib.sh"
[ -n "$JAVA_HOME" ] && export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
[ -n "$ANDROID_HOME" ] && export ANDROID_HOME

cd "$ROOT/launcher"
[ -n "$ANDROID_HOME" ] && echo "sdk.dir=$ANDROID_HOME" > local.properties
PROPS=gradle/wrapper/gradle-wrapper.properties
if [ -n "$GRADLE_ZIP" ] && [ -s "$GRADLE_ZIP" ]; then
  # Build from the local Gradle zip; the repo's own file is put back afterwards.
  cp "$PROPS" "$PROPS.orig"
  trap 'mv -f "$ROOT/launcher/$PROPS.orig" "$ROOT/launcher/$PROPS"' EXIT
  grep -v '^distributionUrl=' "$PROPS.orig" > "$PROPS"
  echo "distributionUrl=file\:$GRADLE_ZIP" >> "$PROPS"
fi
echo "Building..."
./gradlew -q assembleDebug
APK=app/build/outputs/apk/debug/app-debug.apk

connect
echo "Installing..."
if ! OUT=$(adb -s "$TV" install -r "$APK" 2>&1); then
  if echo "$OUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    # Signed with a different key (e.g. the original Plume from GitHub): swap it once. Its settings reset.
    echo "Replacing a differently signed Plume (its settings reset once)..."
    adb -s "$TV" uninstall dev.plume >/dev/null
    adb -s "$TV" install "$APK"
  else
    echo "$OUT"; exit 1
  fi
fi
$A pm grant dev.plume android.permission.WRITE_SECURE_SETTINGS || true
$A pm grant dev.plume android.permission.READ_TV_LISTINGS 2>/dev/null || true
$A appops set dev.plume GET_USAGE_STATS allow
$A appops set dev.plume ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true
$A appops set dev.plume AUTO_START allow 2>/dev/null || true

# Connect the Home button service afresh and check it's running.
SVC="dev.plume/dev.plume.HomeOverride"
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
if [ "$BOUND" = 1 ]; then echo "Home button service: running"; else
  echo "Home button service: NOT running (try scripts/tools/home_service_fix.sh)"
fi

# If Plume is the TV's Home (step 5), a reinstall that had to uninstall first dropped that: put it back.
if [ -f "$STATE/plume_is_home" ]; then
  $A cmd role add-role-holder android.app.role.HOME dev.plume >/dev/null 2>&1 || true
  $A cmd package set-home-activity dev.plume/.MainActivity >/dev/null 2>&1 || true
fi
$A am start -n dev.plume/.MainActivity >/dev/null
echo "Installed. Plume is open on the TV."
