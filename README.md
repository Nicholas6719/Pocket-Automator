# Pocket Automator

Per-game profiles for the **Retroid Pocket Flip 2** (and, once it ships, the
**Retroid Pocket Duo**). Link an emulator to a profile, and while it's in front
the handheld switches to that profile's performance mode, fan mode and other
settings. Leave it, and your **Default** profile comes back.

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
on those settings as soon as they change, so no root is needed.

**Firmware quirk:** the moment performance mode becomes Standard, Retroid's
SystemUI sets the fan to Off. Pocket Automator writes the fan again 0.8 s
after any performance change, so Standard + Smart stays Standard + Smart.

Anything a game's profile changes that Default leaves alone (say, Wi-Fi off)
is put back how it was when you leave the game.

## Auto-close

Emulators you've finished with get closed so they don't pile up in Recents.
Once you leave an emulator (back to ES-DE, say), it is closed 10 seconds
(adjustable) after either:

- the screen turns off, or
- a different emulator opens.

Sleeping mid-game never closes the game you're in, and coming back before the
10 seconds are up keeps it open. Only apps with a profile are ever closed; pick
which ones on the **Auto-close** page. Closing is a force stop (then its Recents
card is cleared), so anything unsaved is lost: save before you leave.

## Your games

If ES-DE is installed, the home screen shows your recently played games and
favorites with their cover art, and each emulator's card shows its own games.
Pocket Automator reads ES-DE's `gamelists` and `downloaded_media` folders
(internal storage or SD card) and never writes to them. It grants itself
"all files access" through Shizuku to do that.

## Requirements

- A Retroid Pocket Flip 2 (tested) or Duo (untested until it ships).
- [Shizuku](https://github.com/RikkaApps/Shizuku/releases). Without root it has
  to be started again after every restart: open Shizuku → *Start via Wireless
  debugging*. Starting it automatically is planned.

## Setup

1. Install the APK and open Pocket Automator.
2. Start Shizuku, then tap **Allow access**. Pocket Automator then grants
   itself "display over other apps" for its switch message, takes itself off
   Retroid's *no auto-run* list, and adds itself to the standby cleaner's
   ignore list.
3. Edit **Default**, add profiles, and link apps under **Choose apps**.

If OdinTools' App overrides are on, Pocket Automator shows a warning: both
apps would switch the same settings.

## Building

JDK 17 and the Android SDK (platform 35):

```bash
./gradlew testDebugUnitTest assembleRelease
```

The APK lands in `app/build/outputs/apk/release/` (about 2 MB; without a
`keystore.properties` it is signed with the local debug key).

## License

GPL-3.0, as the Thor Pathfinder code it adapts (`TaskWatcher`, `AppWatcher`, `Shell`).
