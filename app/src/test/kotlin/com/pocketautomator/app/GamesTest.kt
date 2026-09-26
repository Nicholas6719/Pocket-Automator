package com.pocketautomator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IniTest {

    // As Eden writes a game's file before anything is set for it (trimmed).
    private val eden = """
        |[Renderer]
        |backend\use_global=true
        |resolution_setup\use_global=true
        |use_vsync\use_global=true
        |
        |
        |[GpuDriver]
        |driver_path\use_global=true
        |""".trimMargin()

    @Test
    fun `an Eden value goes in as Eden writes one, and comes out again`() {
        val set = Ini.edenSet(eden, "Renderer", "resolution_setup", "3")
        assertTrue(set.contains("resolution_setup\\use_global=false\nresolution_setup\\default=false\nresolution_setup=3\nuse_vsync"))
        assertTrue(Ini.edenCustom(set, "Renderer", "resolution_setup"))
        assertEquals("3", Ini.get(set, "Renderer", "resolution_setup"))
        assertFalse(Ini.edenCustom(eden, "Renderer", "resolution_setup"))
        assertEquals(eden, Ini.edenClear(set, "Renderer", "resolution_setup"))
    }

    @Test
    fun `a missing Eden file or section is made`() {
        val made = Ini.edenSet("", "GpuDriver", "driver_path", "/x/turnip.zip")
        assertEquals("[GpuDriver]\ndriver_path\\use_global=false\ndriver_path\\default=false\ndriver_path=/x/turnip.zip\n", made)
        val added = Ini.edenSet("[Renderer]\nbackend\\use_global=true\n", "GpuDriver", "driver_path", "")
        assertEquals("[Renderer]\nbackend\\use_global=true\n\n[GpuDriver]\ndriver_path\\use_global=false\ndriver_path\\default=false\ndriver_path=\n", added)
        // Changing a value keeps one copy.
        val twice = Ini.edenSet(made, "GpuDriver", "driver_path", "")
        assertEquals(1, Regex("driver_path=").findAll(twice).count())
    }

    @Test
    fun `Dolphin values sit beside the user's own`() {
        val mine = "[Video_Settings]\nAspectRatio = 1\nwideScreenHack = True\n"
        val set = Ini.dolphinSet(mine, "Video_Settings", "InternalResolution", "3")
        assertEquals("[Video_Settings]\nAspectRatio = 1\nwideScreenHack = True\nInternalResolution = 3\n", set)
        assertEquals(mine, Ini.dolphinRemove(set, "Video_Settings", "InternalResolution"))

        val controls = "[Controls]\nWiimoteProfile1 = Classic Controller\n"
        val added = Ini.dolphinSet(controls, "Video_Settings", "InternalResolution", "2")
        assertEquals("$controls\n[Video_Settings]\nInternalResolution = 2\n", added)
        assertEquals(controls, Ini.dolphinRemove(added, "Video_Settings", "InternalResolution"))
        assertEquals("", Ini.dolphinRemove(Ini.dolphinSet("", "Video_Settings", "InternalResolution", "2"), "Video_Settings", "InternalResolution"))
    }

    @Test
    fun `values are read by section`() {
        val azahar = "[Core]\nuse_cpu_jit = true\n[Renderer]\nresolution_factor = 5\nuse_vsync = true\n"
        assertEquals("5", Ini.get(azahar, "Renderer", "resolution_factor"))
        assertNull(Ini.get(azahar, "Core", "resolution_factor"))
    }
}

class GameIdsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `Switch title IDs from names and tickets`() {
        assertEquals("0100187003A36000", GameIds.switchFromName("Game [0100187003a36800][v1].nsp"))
        assertNull(GameIds.switchFromName("Balatro.nsp"))

        // A PFS0 with a ticket named by rights ID.
        val names = listOf("abcdef.nca", "010018E011D92000000000000000000A.tik")
        val table = names.joinToString("\u0000", postfix = "\u0000").toByteArray()
        val head = java.nio.ByteBuffer.allocate(16 + names.size * 24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        head.put("PFS0".toByteArray()).putInt(names.size).putInt(table.size).putInt(0)
        val nsp = tmp.newFile("x.nsp").apply { writeBytes(head.array() + table) }
        assertEquals("010018E011D92000", GameIds.switchFromNsp(nsp))
        assertNull(GameIds.switchFromNsp(tmp.newFile("y.nsp").apply { writeBytes(ByteArray(64)) }))
    }

    @Test
    fun `GameCube and Wii IDs from disc images`() {
        val rvz = ByteArray(0x80).also { "RVZ\u0001".toByteArray().copyInto(it); "GM4E01".toByteArray().copyInto(it, 0x58) }
        assertEquals("GM4E01", GameIds.dolphin(tmp.newFile("a.rvz").apply { writeBytes(rvz) }))
        val iso = ByteArray(0x20).also { "RMCE01".toByteArray().copyInto(it) }
        assertEquals("RMCE01", GameIds.dolphin(tmp.newFile("b.iso").apply { writeBytes(iso) }))
        assertNull(GameIds.dolphin(tmp.newFile("c.gcz").apply { writeBytes(ByteArray(0x20) { 0x01 }) }))
    }

    @Test
    fun `the title Eden just started`() {
        val launched = """{"010018E011D92000":{"launch_count":7,"timestamp":1788448208},"0100A5C00D162000":{"launch_count":1,"timestamp":1777417966}}"""
        val all = GameIds.edenLaunches(launched)
        assertEquals(2, all.size)
        assertEquals("010018E011D92000", GameIds.startedAfter(all, 1788448200))
        assertNull(GameIds.startedAfter(all, 1788449000))
        assertEquals(emptyMap<String, Long>(), GameIds.edenLaunches("nope"))
    }
}

class HooksTest {

    @Test
    fun `the game ES-DE started`() {
        val e = Hooks.parseEvent("1790450035\nn3ds\n/storage/1A2B-3C4D/ROMs/n3ds/Pokemon Omega Ruby .cci\nPokémon Omega Ruby\n")!!
        assertEquals("n3ds/Pokemon Omega Ruby ", e.id)
        assertEquals("Pokémon Omega Ruby", e.name)
        assertNull(Hooks.parseEvent("garbage"))
        assertNull(Hooks.parseEvent(null))
    }

    @Test
    fun `Azahar's list and the scripts`() {
        val games = listOf(
            GameProfile("n3ds", "Mario Kart 7 ", "Mario Kart 7", "./Mario Kart 7 .cci", emu = mapOf("azahar.resolution" to "3")),
            GameProfile("gc", "F-Zero GX ", "F-Zero GX", "./F-Zero GX .rvz", emu = mapOf("dolphin.resolution" to "2")),
        )
        assertEquals("Mario Kart 7 .cci\tresolution_factor|3\n", Hooks.azaharList(games.associateWith { it.emu }))
        val folder = File("/storage/SD/Pocket Automator")
        assertTrue(Hooks.stub(folder, "start").replace(File.separatorChar, '/').contains("f='/storage/SD/Pocket Automator/on-game.sh'\n[ -f \"\$f\" ] && sh \"\$f\" start \"\$@\""))
        val hook = Hooks.onGame(folder, "/storage/SD/Games/Azahar/config/config.ini")
        assertTrue(hook.contains("p=\$(printf '%s' \"\$1\" | sed 's/\\\\\\(.\\)/\\1/g')"))
        assertEquals("'it'\\''s'", Hooks.quote("it's"))
    }
}

class GamesJsonTest {

    @Test
    fun `game settings round trip, and empty ones drop out`() {
        val games = listOf(
            GameProfile("switch", "Balatro", "Balatro", "./Balatro.nsp", mapOf(Knob.PERFORMANCE to 0), mapOf("eden.resolution" to "3")),
            GameProfile("gc", "Metroid Prime ", "Metroid Prime", "./Metroid Prime .rvz", emu = mapOf("dolphin.resolution" to "2", "bogus" to "1")),
            GameProfile("n3ds", "x", "x", "./x.cci"),
        )
        val back = GamesJson.fromJson(GamesJson.toJson(games))
        assertEquals(2, back.size)
        assertEquals(games[0], back.first { it.system == "switch" })
        assertEquals(mapOf("dolphin.resolution" to "2"), back.first { it.system == "gc" }.emu)
        // ES-DE file names often end in a space; the id has to survive.
        assertEquals("gc/Metroid Prime ", back.first { it.system == "gc" }.id)
    }
}

class SessionsTest {

    // The Flip 2 on 2026-09-26: Shining Pearl last came back at 11:15:04 on 09-03; Eden started it at 11:10:08.
    private val launches = mapOf(
        "010018E011D92000" to 1788448208L,
        "01006F8002326000" to 1788407979L,
        "0100A5C00D162000" to 1777417966L,
    )

    @Test
    fun `a game's last session gives its title`() {
        val ended = mapOf("Pokemon Shining Pearl (1G+1U) " to 1788448504L, "Balatro" to 0L)
        assertEquals("010018E011D92000", GameIds.fromSessions("Pokemon Shining Pearl (1G+1U) ", ended, launches, emptySet()))
        assertNull(GameIds.fromSessions("Balatro", ended, launches, emptySet()))
    }

    @Test
    fun `no match when another game ended in between, or the title is taken`() {
        val ended = mapOf("A" to 1788448504L, "B" to 1788448300L)
        assertNull(GameIds.fromSessions("A", ended, launches, emptySet()))
        assertNull(GameIds.fromSessions("A", mapOf("A" to 1788448504L), launches, setOf("010018E011D92000")))
        // Too long ago to be the same session.
        assertNull(GameIds.fromSessions("A", mapOf("A" to 1788448208L + 9 * 3600), launches, emptySet()))
    }
}

class AzaharSwapTest {

    private val config = "[Core]\ncpu_clock_percentage = \n[Renderer]\nresolution_factor = 5\nshaders_accurate_mul = \n"

    @Test
    fun `swapped values go back, blank ones too`() {
        val swapped = "[Core]\ncpu_clock_percentage = 150\n[Renderer]\nresolution_factor = 3\nshaders_accurate_mul = \n"
        val marker = "cpu_clock_percentage||150\nresolution_factor|5|3\n"
        assertEquals(config, GameSettings.restoreSwaps(swapped, marker))
    }

    @Test
    fun `a value changed in Azahar meanwhile stays`() {
        val changed = "[Core]\ncpu_clock_percentage = 150\n[Renderer]\nresolution_factor = 4\nshaders_accurate_mul = \n"
        val restored = GameSettings.restoreSwaps(changed, "cpu_clock_percentage||150\nresolution_factor|5|3\n")
        assertEquals("[Core]\ncpu_clock_percentage = \n[Renderer]\nresolution_factor = 4\nshaders_accurate_mul = \n", restored)
        assertNull(GameSettings.restoreSwaps(config, "resolution_factor|5|3\n"))
    }
}

class SuggestionsTest {

    private val json = """{"version":1,"hardware":"Snapdragon 865","games":[
        {"system":"switch","name":"Mario Kart 8 Deluxe","id":"0100152000022000","status":"full","note":"n",
         "sources":["https://example.org/a"],
         "settings":[{"key":"eden.accuracy","value":"0","why":"w","source":"s","auto":true},
                     {"key":"eden.resolution","value":"3","why":"w","source":"s","auto":false},
                     {"key":"eden.bogus","value":"1","auto":true}]}]}"""

    @Test
    fun `suggestions parse, and unknown settings drop out`() {
        val s = Suggestions.parse(json).single()
        assertEquals("switch/mariokart8deluxe", s.key)
        // The same game matches however it's written.
        assertEquals(s.key, Suggestions.key("switch", "Mario Kart 8: Deluxe"))
        assertEquals("n3ds/pokemonomegaruby", Suggestions.key("n3ds", "Pokémon Omega Ruby"))
        assertEquals(Suggestion.Status.FULL, s.status)
        assertEquals(listOf("eden.accuracy", "eden.resolution"), s.settings.map { it.key })
    }

    @Test
    fun `yours win, and only automatic suggestions are used`() {
        val s = Suggestions.parse(json).single()
        assertEquals(mapOf("eden.accuracy" to "0"), Suggestions.effective(null, s, auto = true))
        assertEquals(emptyMap<String, String>(), Suggestions.effective(null, s, auto = false))
        assertEquals(mapOf("eden.accuracy" to "1"), Suggestions.effective(mapOf("eden.accuracy" to "1"), s, auto = true))
        assertEquals(emptyMap<String, String>(), Suggestions.effective(mapOf("eden.accuracy" to Suggestions.GLOBAL), s, auto = true))
        assertEquals(mapOf("eden.accuracy" to "0", "eden.resolution" to "3"), Suggestions.effective(mapOf("eden.resolution" to "3"), s, auto = true))
    }
}

class BundledSuggestionsTest {

    private val file = listOf("src/main/assets/suggestions.json", "app/src/main/assets/suggestions.json").map(::File).first { it.isFile }
    private val all = Suggestions.parse(file.readText())

    @Test
    fun `every suggested value is one the setting offers`() {
        assertTrue(all.size > 100)
        val bad = all.flatMap { s ->
            s.settings.filter { r ->
                val knob = EmuKnobs.byKey(r.key)!!
                // The driver's options are the drivers installed; "" is the system's own.
                if (knob.key == EmuKnobs.EDEN_DRIVER.key) r.value != "" else knob.options.none { it.value == r.value }
            }.map { "${s.name}: ${it.key}=${it.value}" }
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun `resolution drops are never applied by themselves`() {
        val resolutions = setOf("eden.resolution", "dolphin.resolution", "azahar.resolution", "armsx2.upscale", "duckstation.resolution")
        assertEquals(emptyList<String>(), all.flatMap { s -> s.settings.filter { it.auto && it.key in resolutions }.map { s.name } })
    }

    @Test
    fun `IDs look right for the emulators that need them`() {
        all.filter { it.system == "ps2" && it.gameId != null }.forEach { assertTrue(it.gameId!!, it.gameId.matches(Regex("[A-Z]{4}-\\d{5}_[0-9A-F]{8}"))) }
        all.filter { it.system == "psx" && it.gameId != null }.forEach { assertTrue(it.gameId!!, it.gameId.matches(Regex("[A-Z]{4}-\\d{5}"))) }
        all.forEach { s -> s.settings.forEach { assertTrue("${s.name} ${it.key}", it.why.isNotBlank() && it.source.startsWith("http")) } }
    }
}

class Ps2IdTest {

    @Test
    fun `serials come from ARMSX2's recent games by ROM name`() {
        val json = """[{"uri":"file:\/\/\/storage\/1A2B-3C4D\/ROMs\/ps2\/Ben%2010%20-%20Alien%20Force%20-%20Vilgax%20Attacks%20.chd","title":"Ben 10","serial":"SLUS-21921","ext":"CHD","platform":"ps2"}]"""
        assertEquals("SLUS-21921", GameIds.ps2SerialFromRecent(json, "Ben 10 - Alien Force - Vilgax Attacks .chd"))
        assertNull(GameIds.ps2SerialFromRecent(json, "Black .chd"))
        assertNull(GameIds.ps2SerialFromRecent("nope", "Black .chd"))
    }

    @Test
    fun `the NCA header key comes from prod keys`() {
        val key = GameIds.headerKey("aes_kek_generation_source = 00\nheader_key = " + "ab".repeat(32) + "\n")!!
        assertEquals(32, key.size)
        assertEquals(0xAB.toByte(), key[0])
        assertNull(GameIds.headerKey("nothing here"))
    }
}

class ChipTest {

    private val json = """{"version":2,"hardware":"Snapdragon 865","socs":["SM8250"],"games":[
        {"system":"gc","name":"Metroid Prime","status":"full","settings":[
          {"key":"dolphin.efb_to_texture","value":"False","why":"rain","source":"https://x","auto":true,"anyChip":true},
          {"key":"dolphin.resolution","value":"2","why":"720p","source":"https://x","auto":false}]},
        {"system":"ps2","name":"Gran Turismo 4","status":"playable","settings":[
          {"key":"armsx2.hw_download","value":"1","why":"speed","source":"https://x","auto":true}]}]}"""

    @Test
    fun `on another chip only fixes for any chip are used`() {
        val (prime, gt4) = Suggestions.parse(json)
        assertEquals(setOf("SM8250"), Suggestions.socs)
        assertEquals(mapOf("dolphin.efb_to_texture" to "False"), Suggestions.effective(null, prime, auto = true, sameChip = false))
        assertEquals(emptyMap<String, String>(), Suggestions.effective(null, gt4, auto = true, sameChip = false))
        assertEquals(mapOf("armsx2.hw_download" to "1"), Suggestions.effective(null, gt4, auto = true, sameChip = true))
    }
}
