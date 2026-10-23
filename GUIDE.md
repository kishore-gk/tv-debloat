# Step-by-step guide

From a stock TCL Google TV to a clean one running the glass launcher. Allow an hour or two the first time, most
of it waiting for downloads. Every step can be undone; see [Undo everything](#undo-everything).

**What you need**

- The TV and a Mac on the same Wi-Fi network. The scripts are written for macOS with [Homebrew](https://brew.sh);
  on Linux they work with the tools installed by hand.
- The TV's remote.
- About 2 GB free on the Mac for the Android build tools.

**What changes on the TV**

| Step | Changes | Undo |
|---|---|---|
| 1. Connect | Developer options and wireless debugging switched on | Switch them off in Settings |
| 3. Debloat | 17 ad, promo and demo packages switched off | `scripts/03_restore.sh` |
| 4. Launcher | One app installed (Plume), with the permissions it needs | `scripts/tools/uninstall_plume.sh` |
| 5. Home | Plume set as the TV's Home app | `scripts/05_restore_google_home.sh` |

Nothing here roots the TV, changes system files, or touches the streaming apps, casting, AirPlay, Miracast,
Dolby audio, the picture processing or the HDMI inputs.

---

## Step 0. Get this repository and set up the Mac

```
git clone <this repository's URL> tv-debloat
cd tv-debloat
bash scripts/00_setup_mac.sh
```

This installs JDK 21, the Android command-line tools and `adb` with Homebrew, then the Android SDK platform 36
(about 1 GB), and writes their paths to `config.sh`. `config.sh` holds your own settings and stays out of git.

If the first build later fails while downloading Gradle (it can time out on slow connections), run
`bash scripts/00_setup_mac.sh --gradle-zip` once. That downloads Gradle with `curl` into `_local/` and the build
uses that copy.

## Step 1. Switch on developer mode and connect

On the TV:

1. **Settings > System > About**, select **Android TV OS build** and press OK seven times, until it says you
   are a developer.
2. **Settings > System > Developer options**: switch on **USB debugging** and **Wireless debugging**.
3. Open **Wireless debugging**. The main screen shows the TV's **IP address and port**. Choose **Pair device
   with pairing code**: that shows a second port and a 6-digit code.

On the Mac, using the numbers from the TV:

```
bash scripts/01_connect.sh <ip> <port> <pairing-port> <code>
# e.g. bash scripts/01_connect.sh 192.168.1.7 42679 37015 123456
```

Accept **Allow debugging?** on the TV (tick "Always allow from this computer"). The script pairs, connects,
switches the connection to the fixed port 5555 and saves `TV=<ip>:5555` in `config.sh`.

Good to know:

- **The pairing port and the connect port are different numbers,** and both change every time the screen
  opens. Connecting to the pairing port is the most common mistake.
- **Port 5555 lasts until the TV restarts.** After a restart, open Wireless debugging again and run
  `bash scripts/01_connect.sh <ip> <port>` with the new port. If it refuses, pair again with all four values.
- **"device offline":** run the same command again; wireless adb sometimes needs a second try.

## Step 2. Take an inventory (read-only)

```
bash scripts/02_inventory.sh
```

It saves the TV's package lists, versions and properties to `_local/reports/inventory-<date>/`. Nothing on the
TV changes. Use it to check that the packages in step 3 exist on your model, and as a record of how the TV
started.

## Step 3. Switch off the ads and promos

Read [`scripts/debloat-packages.txt`](scripts/debloat-packages.txt) first. Each line says what the package
does. The last group is personal choice (Alexa, Game Bar, YouTube Music, Google's ambient screensaver,
MX Player): delete or comment out any line you want to keep.

```
bash scripts/03_debloat.sh
```

- "disable" lines are switched off with `pm disable-user`: the app stays installed but can't run.
- "remove" lines are removed for your user only with `pm uninstall --user 0`. The TV keeps its system copy, so
  `03_restore.sh` can bring them back.
- **Google TV's own launcher is deliberately not on the list.** Disabling it makes this TV boot-loop.

Undo: `bash scripts/03_restore.sh`.

## Step 4. Build and install the launcher

```
bash scripts/04_build_install.sh
```

It builds `launcher/` (the first build downloads Gradle and takes a few minutes), installs it, and sets up
what it needs:

| What | Why |
|---|---|
| `WRITE_SECURE_SETTINGS` | so Plume can switch its Home button service back on if Android drops it |
| `READ_TV_LISTINGS` | to read Continue Watching (Android TV's Watch Next list) |
| `GET_USAGE_STATS` (app op) | to tell which app is in front, so it can cover Google TV's home after the TV starts |
| `AUTO_START` (TCL app op) | TCL blocks apps from starting in the background and resets this on every install; without it Android never starts the Home button service |
| Accessibility service "Plume: Home button" | catches the Home button so it opens Plume |

The script ends by checking that the Home button service is running and opening Plume. Press **Home** on the
remote: you should land on Plume. Run the same command again after any change to `launcher/`.

If the TV had the original Plume from GitHub installed, the script replaces it once (different signing key);
its settings reset.

## Step 5. Make Plume the TV's Home (recommended)

Until now Google TV's launcher is still the TV's official Home app, so Android keeps it loaded in the
background, and Plume covers it. This step makes Plume the official Home: Google TV's launcher stays installed
and enabled, but Android closes it like any app you've left, and the TV starts straight into Plume.

```
bash scripts/05_make_plume_home.sh
```

**Before restarting the TV,** check:

- Home opens Plume.
- The Settings button on the remote still opens the TV's settings.
- Netflix (or any app), casting from a phone and AirPlay still work.

Then restart the TV and check that it starts on Plume.

**It can't brick the TV.** It changes one setting, which app is Home. It doesn't disable, remove or modify
Google TV's launcher, and the TV's two built-in fallback home screens stay. If Plume ever fails to start as
Home:

1. `bash scripts/05_restore_google_home.sh` puts Google TV back as Home.
2. Without adb: **Settings > Apps > Plume > Uninstall**. With Plume gone, Google TV is Home again.
3. Last resort only: a factory reset recovers any software state, but wipes your apps and logins.

## Step 6. Set Plume up

Open Plume's settings with the sliders button at the top left.

- **Weather:** switch it on. The location comes from your internet connection; set the city by hand if it's
  wrong (providers sometimes route through another city). Celsius or Fahrenheit.
- **News:** choose your country's edition, the sections you want, your own topics, and your city for local
  news. Choose how long the home screen sits idle before the news opens (30 seconds by default, or off), and
  whether the morning briefing card appears from 5 to 11 am.
- **Screensaver:** choose **News** to have the news screen as the TV's screensaver.

Around the home screen:

| Where | Press | Does |
|---|---|---|
| Continue Watching | Left / Right | the next or previous show |
| Continue Watching | OK / hold OK | resume / hide the show or open its app |
| Dock | hold OK | move an app, remove it from the dock, app info |
| Top bar, right of the settings button | OK on the date | calendar with holidays |
| Down from the date | OK on the weather | 7-day forecast |
| News screen | hold OK on a section | move it left or right, or remove it |
| Anywhere | Home / hold Home | back to Plume / Google TV's notifications |

## Step 7. Lighten the TV further (optional)

Measure first, change one thing, measure again:

```
bash scripts/tools/audit.sh
```

Leave the TV on Plume, untouched, for two minutes before running it. The report goes to `_local/reports/`.

What we found on this TV:

- **The load average looks alarming (about 18) but isn't real load.** About 17 of the TV's driver threads (HDMI,
  panel, memory controller, audio output) wait permanently in the kernel, and Linux counts waiting threads.
  The processor was about 87% idle. Read the `%idle` line in the report, not the load average.
- **Memory is the real limit.** The TV has 2.4 GB in total, and only about 600 MB was free. Step 5 frees Google
  TV's launcher.
- **Google Assistant's hands-free listening ("Hey Google") used about a quarter of one core all the time.** If
  you don't use hands-free voice, switch off "Hey Google" in the TV's Google Assistant settings, or with the
  microphone switch on the TV if it has one. The voice button on the remote keeps working.

Leave alone: casting (`com.google.android.apps.mediashell`), AirPlay (`com.tcl.airplay2`,
`com.mediatek.airplaydaemon`), Miracast (`com.tcl.miracast`), Dolby and speaker services, `com.tcl.tvinput`,
and the Google TV phone remote service. Apps you've used stay in memory after you leave them; that's normal,
and Android frees that memory when it needs it.

## Step 8. Check everything still works

- [ ] Netflix, JioHotstar, SonyLIV, Spotify, YouTube and Prime Video open and play
- [ ] Continue Watching shows what you were watching in those apps
- [ ] Casting from a phone (YouTube, Netflix) works
- [ ] AirPlay from an iPhone or Mac works, including screen mirroring
- [ ] Picture and sound modes look and sound as before
- [ ] Home opens Plume; holding Home opens the notifications
- [ ] After a restart, the TV starts on Plume (after step 5)

---

## Troubleshooting

**Home opens Google TV instead of Plume.** Run `bash scripts/tools/home_service_fix.sh`. The usual cause on
TCL is the `AUTO_START` permission, which TCL resets on every install. The script sets it, reconnects the Home
button service and reports whether it's running.

**Can't reach the TV.** The TV restarted and port 5555 is gone, or its IP changed. See step 1. A fixed IP for
the TV in your router's settings saves updating `config.sh`.

**Gradle download times out.** Run `bash scripts/00_setup_mac.sh --gradle-zip` once.

**Something odd at start-up** (the wrong app opens, focus jumps). Straight after it happens, run
`bash scripts/tools/boot_diag.sh`. It saves crashes, which app started which and from where, and Plume's focus
log. Plume logs any focus move that didn't come from a key press; `bash scripts/tools/focus_log.sh` prints it.

**Measuring smoothness.** `bash scripts/tools/framerate.sh` (you move around for 20 seconds) or
`bash scripts/tools/bench.sh` (it presses the keys itself, the same every run). This TV renders its interface at
1080p, 60 Hz, and the graphics chip is the limit, which is why the launcher avoids live blur and layout
animation.

**Screenshots.** `bash scripts/tools/screenshot.sh <name>` saves to `_local/shots/`.

## Undo everything

In this order:

```
bash scripts/05_restore_google_home.sh   # Google TV is Home again
bash scripts/tools/uninstall_plume.sh    # remove the launcher
bash scripts/03_restore.sh               # switch the packages from step 3 back on
```

Then switch off Wireless debugging and USB debugging in **Settings > System > Developer options**.

## Changing the launcher

The source is in `launcher/` (Kotlin, no third-party libraries). [`launcher/CHANGES.md`](launcher/CHANGES.md)
lists what this fork changed; the git history has the details. Rebuild and install with
`bash scripts/04_build_install.sh`. The interface sounds are generated: edit and run
`python3 launcher/tools/gen_sounds.py` (needs numpy and scipy).
