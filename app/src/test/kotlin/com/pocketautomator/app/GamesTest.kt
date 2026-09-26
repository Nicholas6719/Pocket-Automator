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
        val e = Hooks.parseEvent("1790450035\nn3ds\n/storage/75D7-DC5F/ROMs/n3ds/Pokemon Omega Ruby .cci\nPokémon Omega Ruby\n")!!
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
        assertEquals("Mario Kart 7 .cci\t3\n", Hooks.azaharList(games))
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
