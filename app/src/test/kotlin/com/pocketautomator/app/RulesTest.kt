package com.pocketautomator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppRulesTest {

    private val own = "com.pocketautomator.app"
    private val game = TaskEntry(11, 0, true, TaskList.TYPE_STANDARD, "com.armsx2/.MainActivity")
    private val launcher = TaskEntry(1, 0, true, TaskList.TYPE_HOME, "com.retroidpocket.gamelauncher/.Main")
    private val browser = TaskEntry(12, 0, true, TaskList.TYPE_STANDARD, "com.brave.browser/.Main")
    private val recents = TaskEntry(2, 0, true, TaskList.TYPE_RECENTS, "com.android.systemui/.Recents")
    private val me = TaskEntry(13, 0, true, TaskList.TYPE_STANDARD, "$own/.ui.MainActivity")
    private val linked = { pkg: String -> pkg == "com.armsx2" }

    @Test
    fun `the focused app is picked`() {
        assertEquals(game, AppRules.pick(game, listOf(game), own, linked))
        assertEquals(browser, AppRules.pick(browser, listOf(browser), own, linked))
        assertEquals(launcher, AppRules.pick(launcher, listOf(launcher), own, linked))
    }

    @Test
    fun `recents and this app keep the profile in use`() {
        assertNull(AppRules.pick(recents, listOf(recents, game), own, linked))
        assertNull(AppRules.pick(me, listOf(me, game), own, linked))
    }

    @Test
    fun `a linked game showing on another screen wins over the focused one`() {
        val onTop = game.copy(display = 0)
        val bottomLauncher = launcher.copy(display = 4)
        assertEquals(onTop, AppRules.pick(bottomLauncher, listOf(bottomLauncher, onTop), own, linked))
    }

    @Test
    fun `a game in the background doesn't count`() {
        val hidden = game.copy(visible = false)
        assertEquals(launcher, AppRules.pick(launcher, listOf(launcher, hidden), own, linked))
    }

    @Test
    fun `task lines round trip`() {
        val line = TaskList.line(11, 0, true, 1, "com.armsx2/.MainActivity")
        assertEquals(game, TaskList.parse(line))
        assertNull(TaskList.parse("garbage"))
        assertNull(TaskList.parse(""))
        assertEquals(null, TaskList.parse("3|0|true|1|")?.component)
    }
}

class CommandsTest {

    @Test
    fun `readings from the Flip 2`() {
        // What the Flip 2 printed on 2026-09-26: Performance, Smart fan, Both, manual brightness 51.
        val out = "1\n4\n2\n0\n51\nnull\n1\n1\n0\n4500\n"
        val r = Commands.parse(out)
        assertEquals(Knob.PERF_PERFORMANCE, r.values[Knob.PERFORMANCE])
        assertEquals(Knob.FAN_SMART, r.values[Knob.FAN])
        assertEquals(Knob.TRIGGERS_BOTH, r.values[Knob.TRIGGERS])
        assertEquals(51, r.values[Knob.BRIGHTNESS])
        assertEquals(Knob.REFRESH_DEFAULT, r.values[Knob.REFRESH])
        assertEquals(Knob.DND_PRIORITY, r.values[Knob.DND])
        assertEquals(1, r.values[Knob.WIFI])
        assertEquals(0, r.values[Knob.BLUETOOTH])
        assertEquals(4500, r.fanRpm)
    }

    @Test
    fun `auto brightness and never-set values`() {
        val r = Commands.parse("null\nnull\nnull\n1\n120\n120.0\n0\n2\n2\nnull\n")
        assertEquals(null, r.values[Knob.PERFORMANCE])
        assertEquals(Knob.TRIGGERS_BOTH, r.values[Knob.TRIGGERS])
        assertEquals(Knob.BRIGHTNESS_AUTO, r.values[Knob.BRIGHTNESS])
        assertEquals(120, r.values[Knob.REFRESH])
        assertEquals(1, r.values[Knob.WIFI])
        assertEquals(null, r.fanRpm)
    }

    @Test
    fun `fan writes set the smart switch first, as the tile does`() {
        assertEquals(
            listOf("settings put system smart_fan_mode_switch 1", "settings put system fan_mode 4"),
            Commands.write(Knob.FAN, Knob.FAN_SMART),
        )
        assertEquals(
            listOf("settings put system smart_fan_mode_switch 0", "settings put system fan_mode 5"),
            Commands.write(Knob.FAN, Knob.FAN_SPORT),
        )
    }

    @Test
    fun `the fan waits for a performance change`() {
        assertEquals(
            "settings put system performance_mode 0; sleep 0.8; settings put system smart_fan_mode_switch 1; settings put system fan_mode 4",
            Commands.script(linkedMapOf(Knob.PERFORMANCE to 0, Knob.FAN to 4)),
        )
        assertEquals(
            "settings put system smart_fan_mode_switch 0; settings put system fan_mode 5",
            Commands.script(linkedMapOf(Knob.FAN to 5)),
        )
    }

    @Test
    fun `other writes`() {
        assertEquals(listOf("settings put system performance_mode 2"), Commands.write(Knob.PERFORMANCE, 2))
        assertEquals(listOf("cmd notification set_dnd off"), Commands.write(Knob.DND, Knob.DND_OFF))
        assertEquals(listOf("cmd wifi set-wifi-enabled disabled"), Commands.write(Knob.WIFI, 0))
        assertEquals(listOf("cmd bluetooth_manager enable"), Commands.write(Knob.BLUETOOTH, 1))
        assertEquals(listOf("settings put system screen_brightness_mode 1"), Commands.write(Knob.BRIGHTNESS, Knob.BRIGHTNESS_AUTO))
        assertEquals(
            listOf("settings delete system min_refresh_rate", "settings delete system peak_refresh_rate"),
            Commands.write(Knob.REFRESH, Knob.REFRESH_DEFAULT),
        )
        assertEquals(
            "settings put system performance_mode 0; settings put system trigger_input_mode 1",
            Commands.script(linkedMapOf(Knob.PERFORMANCE to 0, Knob.TRIGGERS to 1)),
        )
    }
}

class DeviceTest {

    @Test
    fun `models are told apart`() {
        assertEquals(Device.Model.FLIP2, Device.model("Moorechip", "Retroid Pocket Flip2"))
        assertEquals(Device.Model.DUO, Device.model("Moorechip", "Retroid Pocket Duo"))
        assertEquals(Device.Model.OTHER_RETROID, Device.model("Moorechip", "Retroid Pocket Duo Lite"))
        assertEquals(Device.Model.OTHER_RETROID, Device.model("Moorechip", "Retroid Pocket 5"))
        assertEquals(Device.Model.UNSUPPORTED, Device.model("AYN", "AYN Thor"))
    }

    @Test
    fun `brightness percent round trips`() {
        for (p in 1..100) assertEquals(p, Device.brightnessPercent(Device.brightnessRaw(p)))
        assertEquals(20, Device.brightnessPercent(51))
    }

    @Test
    fun `refresh rate and game screen only where the device has them`() {
        assertEquals(false, Knob.REFRESH in Device.knobs(listOf(60), 1))
        assertEquals(true, Knob.REFRESH in Device.knobs(listOf(60, 120), 2))
        assertEquals(false, Knob.GAME_SCREEN in Device.knobs(listOf(60), 1))
        assertEquals(true, Knob.GAME_SCREEN in Device.knobs(listOf(60), 2))
    }

    @Test
    fun `summary message`() {
        val p = Profile(1, "Heavy games", mapOf(Knob.PERFORMANCE to Knob.PERF_HIGH, Knob.FAN to Knob.FAN_SMART))
        assertEquals("Heavy games · High Performance · Smart fan", Automator.message(p))
    }
}

class RetroidListsTest {

    @Test
    fun `only this app's entry changes`() {
        val me = "com.pocketautomator.app"
        assertEquals("a,b", RetroidLists.without("a,$me,b", me))
        assertEquals(null, RetroidLists.without("a,b", me))
        assertEquals(null, RetroidLists.without("null", me))
        assertEquals("a,$me", RetroidLists.with("a", me))
        assertEquals(me, RetroidLists.with("null", me))
        assertEquals(null, RetroidLists.with("$me,a", me))
    }
}

class ProfilesJsonTest {

    @Test
    fun `profiles round trip`() {
        val profiles = listOf(
            Profile(0, "Default", mapOf(Knob.PERFORMANCE to 1, Knob.FAN to 4)),
            Profile(3, "Heavy", mapOf(Knob.PERFORMANCE to 2, Knob.WIFI to 0), setOf("com.armsx2", "org.dolphinemu.dolphinemu")),
        )
        assertEquals(profiles, ProfilesJson.fromJson(ProfilesJson.toJson(profiles).toString()))
    }

    @Test
    fun `a broken file is refused, and a partial one repaired`() {
        assertNull(ProfilesJson.fromJson("{}"))
        assertNull(ProfilesJson.fromJson("not json"))
        val text = """{"format":"pocket-automator-profiles","profiles":[
            {"id":2,"name":"A","settings":{"performance":2,"bogus":1},"apps":["x","y"]},
            {"id":3,"name":"B","apps":["y","z"]},
            {"id":3,"name":"dup"}]}"""
        val p = ProfilesJson.fromJson(text)!!
        assertEquals(listOf(0, 2, 3), p.map { it.id })
        assertEquals(mapOf(Knob.PERFORMANCE to 2), p[1].settings)
        // "y" stays with the first profile that claimed it.
        assertEquals(setOf("z"), p[2].apps)
    }
}

class BackgroundTest {

    @Test
    fun `the watchdog's list names Syncthing's service and wakes other apps`() {
        assertEquals(
            "com.github.catfriend1.syncthingfork com.github.catfriend1.syncthingfork/com.nutomic.syncthingandroid.service.SyncthingService\n" +
                "org.example.app\n",
            Background.keepAliveFile(listOf("org.example.app", "com.github.catfriend1.syncthingfork")),
        )
        assertEquals("\n", Background.keepAliveFile(emptyList()))
    }

    @Test
    fun `protection lifts Android's background limits for one app`() {
        assertEquals(
            listOf(
                "dumpsys deviceidle whitelist +org.example.app",
                "cmd appops set org.example.app RUN_ANY_IN_BACKGROUND allow",
                "cmd appops set org.example.app RUN_IN_BACKGROUND allow",
                "am set-standby-bucket org.example.app active",
            ),
            Background.protectCommands("org.example.app"),
        )
    }

    @Test
    fun `Shizuku is started from its own library`() {
        assertEquals(
            "lib=\$(pm path moe.shizuku.privileged.api | head -n 1 | sed 's/^package://; s/base\\.apk\$//')lib/arm64/libshizuku.so; [ -f \"\$lib\" ] && \"\$lib\"",
            Background.START_SHIZUKU,
        )
    }
}
