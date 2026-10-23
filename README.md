# Clean TCL Google TV

Remove the ads and promos from a TCL Google TV and replace its home screen with a quiet, glass-styled launcher:
your apps, what you were watching, the time, the weather, and a news screen. All from a Mac on the same Wi-Fi,
with no root and nothing that can't be undone.

Everything you watch keeps working as before: Netflix, JioHotstar, SonyLIV, Spotify, YouTube and Prime Video,
casting from a phone (Chromecast), AirPlay from an iPhone or Mac, and the TV's picture and sound processing.
Nothing here touches those.

**Follow the [step-by-step guide](GUIDE.md).**

## What you end up with

- **No ads or promos:** TCL's promotional feeds, pop-ups, demo modes and Google TV's promoted-app installer are
  switched off (17 packages, listed with what each one does in
  [scripts/debloat-packages.txt](scripts/debloat-packages.txt)).
- **A new home screen,** a modified fork of [Plume Launcher](https://github.com/aradhyas8/Plume_Launcher)
  in [launcher/](launcher/):
  - Continue Watching full screen, tvOS style, with the show, the time left and a Resume button
  - a frosted glass dock of your apps
  - a big clock, the date and the weather
  - a newspaper-style news screen that also works as the screensaver
  - a calendar with holidays, and a 7-day forecast
  - See [launcher/CHANGES.md](launcher/CHANGES.md) for the full list.
- **Plume as the TV's real Home,** so Google TV's launcher no longer stays loaded alongside it, and the TV starts
  straight into Plume. The Home button opens Plume; holding it still opens Google TV's notifications panel.

## Tested on

TCL 55" Smart TV Pro, Google TV, Android TV OS 14 (build `tcl9618 UTT2.250416.001`), driven from a Mac with
Homebrew. Steps 1, 2, 4 and 5 of the guide should work on most Google TV devices; the package list in step 3 is
specific to TCL, so check it against your own TV's inventory first.

## Layout

```
GUIDE.md                  the step-by-step guide
config.example.sh         copy to config.sh: the TV's address and build tool paths
scripts/
  00_setup_mac.sh         one time: JDK 21 and the Android SDK
  01_connect.sh           pair and connect adb over Wi-Fi
  02_inventory.sh         read-only snapshot of the TV's packages
  03_debloat.sh           switch off the packages in debloat-packages.txt   (undo: 03_restore.sh)
  04_build_install.sh     build the launcher and install it with its permissions
  05_make_plume_home.sh   make Plume the TV's Home                          (undo: 05_restore_google_home.sh)
  tools/                  audit, diagnostics, screenshots, frame rate, uninstall
launcher/                 the launcher's source (Android, Kotlin), with its own history
_local/                   yours only, not in git: reports, screenshots, downloads
```

## Undo

Every step has an undo, listed at the [end of the guide](GUIDE.md#undo-everything). Uninstalling the launcher
on its own also brings Google TV's home screen back.

## License

GPL-3.0, the same as Plume Launcher (see [LICENSE](LICENSE)). The launcher is Aradhya's
[Plume Launcher](https://github.com/aradhyas8/Plume_Launcher), modified; its full history, with the original
author's commits, is kept in this repository.
