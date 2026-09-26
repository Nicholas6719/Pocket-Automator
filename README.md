# Pocket Automator

Per-game profiles for the **Retroid Pocket Flip 2** (and, once it ships, the
**Retroid Pocket Duo**). Link an emulator to a profile, and while it's in front
the handheld switches to that profile's performance mode, fan mode and other
settings. Leave it, and your **Default** profile comes back. Emulators you've
finished with close themselves, and the apps you care about keep running 24/7.

Built on the app-watching approach of
[Thor Pathfinder](https://github.com/KaitonGxx/thor-pathfinder) (GPL-3.0), retargeted from button
shortcuts on the AYN Thor to performance/fan profiles on Retroid handhelds.

## What a profile can set

Every setting can be left on **Don't change**.

| Setting | Values | How |
|---|---|---|
| Performance mode | Standard, Performance, High Performance | `Settings.System performance_mode` 0/1/2 (Retroid's own) |
| Fan | Quiet, Smart, Sport, Off | `fan_mode` 1/4/5/0 plus `smart_fan_mode_switch` (Retroid's own) |
| L2/R2 mode | Analog, Digital, Both | `trigger_input_mode` 0/1/2 (Retroid's own) |
| Brightness | Auto, or a level | `screen_brightness(_mode)` |
| Do Not Disturb | On, Off | `cmd notification set_dnd` |
| Wi-Fi, Bluetooth | On, Off | `cmd wifi` / `cmd bluetooth_manager` |
| Refresh rate | Rates the screen supports (hidden on the 60 Hz Flip 2) | `peak/min_refresh_rate` |
| Game screen (Duo) | Top, Bottom | `am display move-stack` |

The values were read off a Flip 2 (firmware RPFlip2_V1.0.0.130, Android 13):
the Quick Settings tiles, the settings they write, and the CPU limits and fan
duty that follow. Retroid's settings app (`com.rp.settings`) and SystemUI act
on those settings as soon as they change.

**Firmware quirk:** the moment performance mode becomes Standard, Retroid's
SystemUI sets the fan to Off. Pocket Automator writes the fan again 0.8 s
after any performance change, so Standard + Smart stays Standard + Smart.

Anything a game's profile changes that Default leaves alone (say, Wi-Fi off)
is put back how it was when you leave the game. A short message shows each
switch, over the game.

## Auto-close

Emulators you've finished with get closed so they don't pile up in Recents.
Quit a game and its leftover card is cleared from Recents within a few
seconds. Leave one still running, and it is closed 10 seconds (adjustable)
after any of:

- you're back in ES-DE (the home screen), screen on or off ("Close games
  left in ES-DE" on the Auto-close page; on by default),
- the screen turns off, or
- a different emulator opens.

Sleeping mid-game never closes the game you're in, and coming back before the
10 seconds are up keeps it open. An emulator counts as left behind whenever it
has a Recents card and isn't in front, so games already in the background when
Pocket Automator restarts (after an update, say) still close. Pick which emulators it applies to on the
**Auto-close** page, and **Add apps** without a profile (a browser, say) to
close them the same way. Home screens (ES-DE), Shizuku and Pocket Automator
are never closed. Closing is a force stop (then its Recents card is cleared),
so anything unsaved is lost: save before you leave.

## Keep alive

Pocket Automator starts Shizuku by itself (after a restart, or if it stops),
and a small watchdog checks every 20 seconds that Shizuku, Pocket Automator
and the apps on the **Keep alive** list (Syncthing Fork by default) are
running, starting any that have stopped, including after a swipe in Recents
or a force stop. One switch on the Keep alive page turns it all off.

This uses Retroid's built-in root service (`PServerBinder`, the one behind
"Run script as Root" in Retroid's settings): nothing is rooted or unlocked.
Two things to know about that service:

- **Any app can use it**, not just Pocket Automator. That's how Retroid's
  firmware is built; be careful what you install from unknown sources.
- It cuts commands off at about 255 characters and stops them at their
  second line of output. So for now the watchdog can't be updated in place,
  the battery and standby-cleaner exemptions don't apply, and the Keep alive
  page shows one line of the watchdog's log.

## Your games

If ES-DE is installed, the home screen's **Jump back in** row shows your
recently played games with their cover art (and each game's performance mode),
and each emulator's card shows its own games and what you played last.
Pocket Automator reads ES-DE's `gamelists` and `downloaded_media` folders
(internal storage or SD card) and never writes to them; it grants itself
"all files access" through Shizuku to do that.

## Game profiles

Tap a game (in Jump back in, the **Games** page, or on an emulator's
profile) to give it settings of its own. ES-DE stays your frontend:

- **Handheld**: performance, fan and the rest, on top of its emulator's
  profile. Anything left on "Same as profile" follows the profile.
- **Emulator**: graphics and CPU settings for five emulators:
  - **Eden**: resolution, GPU accuracy, console mode, CPU accuracy, ASTC
    decoding, asynchronous shaders, GPU driver (only drivers already
    installed), and more (CPU/GPU clocks, VSync, frame pacing, DMA
    accuracy, VRAM, memory layout, anti-aliasing and others).
  - **Dolphin**: resolution, shader compilation, EFB access and copies,
    dual core, texture cache, CPU clock override, widescreen hack, and more.
  - **Azahar**: resolution, CPU clock, accurate multiplication, shaders,
    texture filter and screen layout.
  - **ARMSX2**: resolution, EE cycle rate and skip, blending accuracy,
    hardware download mode, MTVU, texture filtering and upscaling fixes.
  - **DuckStation**: resolution, widescreen, aspect ratio, PGXP and CPU overclock.

How it knows the game: ES-DE runs "custom event scripts" as a game starts and
ends. Pocket Automator puts a small script in ES-DE's `scripts/game-start`
and `scripts/game-end` folders that writes the game down in a
**Pocket Automator** folder beside ES-DE's. The Games page shows whether ES-DE
has custom event scripts on (Menu → Other settings), and can turn it on.

Where emulator settings go (each was checked on a Flip 2 by starting a game
and seeing the setting take effect):

| Emulator | Where | How the game is found |
|---|---|---|
| Eden | `config/custom/<title ID>.ini`, Eden's own per-game settings | Title ID read from the cart: an NSP's ticket, or an XCI's program NCA header (decrypted with Eden's own `prod.keys`); failing that, the file name, the bundled table, your last session, or learned the first time the game starts from ES-DE |
| Dolphin | `GameSettings/<game ID>.ini`, loaded over the fixes Dolphin ships for the game | Game ID read from the disc image (ISO, RVZ, WIA, WBFS, CISO) |
| Azahar | No per-game settings on Android, so the hook swaps each setting into `config.ini` as the game starts, and puts Azahar's own back once you're in ES-DE again (or Azahar closes) | ROM file name |
| ARMSX2 | `gamesettings/<serial>_<CRC>.ini` in ARMSX2's folder, which the PS2 core loads over ARMSX2's settings | Serial and CRC from the bundled table (made from PCSX2's game list), or ARMSX2's recent games plus its patch archive |
| DuckStation | `gamesettings/<serial>.ini`, DuckStation's own per-game settings | Serial from the bundled table |

### Community settings

`app/src/main/assets/suggestions.json` holds community reports for 225 games
(matched by system and title as ES-DE shows it): how each runs on a Snapdragon
865 (Retroid Pocket 5, Mini, Flip 2) and the settings its sources use, each
with the reason and a link. It was gathered from an RP5 compatibility
spreadsheet, Flip 2 and RP5 guides and videos, the Dolphin wiki's game
pages, ARMSX2's release notes and issues, and DuckStation's game database.
(EmuReady wasn't used.)

- A game's page shows its report and marks the community's value on each
  setting. The Games page lists the games that are heavy on this chip.
- **Auto, but never over yours:** only fixes a source calls necessary are
  applied by themselves (for example, GPU accuracy for Yoshi's Crafted World,
  "No readbacks" for Gran Turismo 4, EFB copies for Metroid Prime's rain).
  Lower resolutions are only ever suggested. Anything you pick, "Same as" the
  emulator included, wins, and one switch on the Games page turns community
  settings off.
- Emulators also apply their own community databases (Dolphin's per-game
  INIs, PCSX2's GameIndex and ARMSX2's mobile overrides, Azahar's per-game
  fixes, PPSSPP's compatibility list); the bundled reports don't repeat those.

**Never over yours:** a setting you've given a game in the emulator itself is
left alone (the game's page says so), and Pocket Automator only takes back
values it wrote that are still there. Resetting a game puts the emulator's
file back as it was.

**Always a way back**, with or without the app:

- A game's page: **Reset this game**. The Games page: **Undo all emulator
  changes** (which also turns community settings off).
- The **Pocket Automator** folder beside ES-DE's has `backups/` (each
  emulator file as it was before its first change), a `README.txt`, and
  `Undo game settings.sh`, which takes every change back (and removes the
  ES-DE hook) without the app: run it from Retroid's settings (Run script
  as Root). Deleting the folder turns the hook off.
- Back up / Restore on the home screen includes games' settings, so they
  move between handhelds.

## Requirements

- A Retroid Pocket Flip 2 (tested) or Duo (untested until it ships).
- [Shizuku](https://github.com/RikkaApps/Shizuku/releases), installed and
  allowed once. With Keep alive on, Pocket Automator starts it from then on.

## Setup

1. Install the APK and open Pocket Automator.
2. Start Shizuku once, then tap **Allow access**. Pocket Automator then grants
   itself "display over other apps" for its switch message and "all files
   access" for ES-DE's art, and takes itself off Retroid's cleaner lists.
3. Edit **Default**, add profiles, and link apps under **Choose apps**.

If OdinTools' App overrides are on, Pocket Automator shows a warning: both
apps would switch the same settings.

## Building

JDK 17 and the Android SDK (platform 35):

```bash
./gradlew testDebugUnitTest assembleRelease
```

The APK lands in `app/build/outputs/apk/release/` (about 2 MB).

**Signing:** Android only installs an update over the app if it's signed with
the same key. Builds are signed with the key named in `keystore.properties`
(gitignored) in the project root:

```
storeFile=C:/path/to/pocket-automator.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without that file, builds fall back to the local debug key, which won't
update a copy signed with another key.

## License

GPL-3.0, as the Thor Pathfinder code it adapts (`TaskWatcher`, `AppWatcher`, `Shell`).
