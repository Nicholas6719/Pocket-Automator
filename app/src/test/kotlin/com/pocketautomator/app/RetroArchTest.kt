package com.pocketautomator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RetroArchTest {

    @Test
    fun `games find their core from ES-DE's label, else the system's default`() {
        assertEquals(Emulator.SWANSTATION, Emulator.forGame("com.retroarch.aarch64", "SwanStation", "psx"))
        assertEquals(Emulator.MUPEN64, Emulator.forGame("com.retroarch.aarch64", null, "n64"))
        assertEquals(Emulator.FLYCAST, Emulator.forGame("com.retroarch.aarch64", null, "dreamcast"))
        assertEquals(Emulator.FLYCAST, Emulator.forGame("com.retroarch.aarch64", "Flycast", "naomi"))
        // ES-DE's PS1 default is Beetle PSX: not a core Pocket Automator sets.
        assertNull(Emulator.forGame("com.retroarch.aarch64", null, "psx"))
        assertNull(Emulator.forGame("com.retroarch.aarch64", "ParaLLEl N64", "n64"))
        assertNull(Emulator.forGame("com.retroarch.aarch64", "mGBA", "gba"))
        // Standalone apps by their package; RetroArch's package alone isn't a core.
        assertEquals(Emulator.DUCKSTATION, Emulator.forGame("com.github.stenzek.duckstation", "DuckStation (Standalone)", "psx"))
        assertNull(Emulator.of("com.retroarch.aarch64"))
        assertNull(Emulator.forGame(null, "SwanStation", "psx"))
    }

    @Test
    fun `retroarch cfg gives the folders and switches`() {
        val cfg = """
            rgui_config_directory = "/storage/emulated/0/RetroArch/config"
            game_specific_options = "true"
            global_core_options = "false"
            core_options_path = ""
        """.trimIndent()
        val path = "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg"
        val s = RetroArch.setup(cfg, path)
        assertEquals("/storage/emulated/0/RetroArch/config", s.configDir)
        assertTrue(s.gameOptions)
        assertTrue(s.perCore)
        assertEquals("/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch-core-options.cfg", s.globalFile)
        assertEquals("/storage/emulated/0/RetroArch/config/SwanStation/Crash Bash .opt", s.gameFile(Emulator.SWANSTATION, "Crash Bash .chd"))
        assertEquals("/storage/emulated/0/RetroArch/config/Mupen64Plus-Next/Mupen64Plus-Next.opt", s.coreFile(Emulator.MUPEN64))

        val off = RetroArch.setup("game_specific_options = \"false\"\nglobal_core_options = \"true\"\n", path)
        assertFalse(off.gameOptions)
        assertFalse(off.perCore)
        assertEquals("/storage/emulated/0/RetroArch/config", off.configDir)
    }

    @Test
    fun `options files read and write the way RetroArch does`() {
        val text = "reicast_widescreen_hack = \"disabled\"\n# comment\nreicast_alpha_sorting = \"per-triangle (normal)\"\n"
        val opts = RetroArch.parse(text)
        assertEquals("per-triangle (normal)", opts["reicast_alpha_sorting"])
        assertEquals(2, opts.size)
        // Sorted, quoted, one per line.
        assertEquals(
            "reicast_alpha_sorting = \"per-triangle (normal)\"\nreicast_widescreen_hack = \"disabled\"\n",
            RetroArch.render(opts),
        )
        assertEquals(opts, RetroArch.parse(RetroArch.render(opts)))
    }

    @Test
    fun `a core's options come from its own file, else its lines in the file for all cores`() {
        val setup = RetroArch.Setup("/c", gameOptions = true, perCore = true, globalFile = "/g.cfg")
        val files = mutableMapOf(
            "/g.cfg" to "swanstation_GPU_ResolutionScale = \"2\"\nmupen64plus-aspect = \"4:3\"\n",
        )
        // No core file yet: the global file's SwanStation lines only.
        assertEquals(mapOf("swanstation_GPU_ResolutionScale" to "2"), RetroArch.base(setup, Emulator.SWANSTATION, files::get))
        files["/c/SwanStation/SwanStation.opt"] = "swanstation_GPU_ResolutionScale = \"4\"\nswanstation_GPU_PGXPEnable = \"false\"\n"
        assertEquals("4", RetroArch.base(setup, Emulator.SWANSTATION, files::get)["swanstation_GPU_ResolutionScale"])
        // One file for all cores: the core file isn't used.
        assertEquals("2", RetroArch.base(setup.copy(perCore = false), Emulator.SWANSTATION, files::get)["swanstation_GPU_ResolutionScale"])
    }

    @Test
    fun `a file is still Pocket Automator's while it has what it wrote`() {
        val written = mapOf("a" to "1", "b" to "2")
        assertTrue(RetroArch.unchanged(mapOf("a" to "1", "b" to "2"), written))
        // RetroArch adds options a newer core has, and drops ones it no longer has.
        assertTrue(RetroArch.unchanged(mapOf("a" to "1", "b" to "2", "c" to "3"), written))
        assertTrue(RetroArch.unchanged(mapOf("a" to "1"), written))
        // Changed in RetroArch: yours now.
        assertFalse(RetroArch.unchanged(mapOf("a" to "1", "b" to "5"), written))
    }

    @Test
    fun `every RetroArch setting is one of its core's options`() {
        for (knob in EmuKnobs.all.filter { it.emulator.isRetroArch }) {
            assertTrue(knob.key, knob.name.startsWith(knob.emulator.prefix))
            assertTrue(knob.key, knob.options.isNotEmpty())
            assertTrue(knob.key, knob.default == null || knob.options.any { it.value == knob.default })
        }
    }

    @Test
    fun `DuckStation choices carry over to SwanStation, and settings for other emulators drop out`() {
        val emu = mapOf(
            "duckstation.resolution" to "4",
            "duckstation.aspect" to "Auto (Game Native)",
            "duckstation.pgxp" to Suggestions.GLOBAL,
            "duckstation.overclock" to "true",
            "eden.resolution" to "5",
        )
        assertEquals(
            mapOf("swanstation.resolution" to "4", "swanstation.aspect" to "Auto", "swanstation.pgxp" to Suggestions.GLOBAL),
            EmuKnobs.forEmulator(emu, Emulator.SWANSTATION),
        )
        // A SwanStation choice of its own wins over the DuckStation one.
        assertEquals("2", EmuKnobs.forEmulator(emu + ("swanstation.resolution" to "2"), Emulator.SWANSTATION)["swanstation.resolution"])
        // And back again.
        assertEquals("Auto (Game Native)", EmuKnobs.forEmulator(mapOf("swanstation.aspect" to "Auto"), Emulator.DUCKSTATION)["duckstation.aspect"])
        // Unknown emulator: nothing is left out.
        assertEquals(emu, EmuKnobs.forEmulator(emu, null))
    }

    @Test
    fun `the undo script removes Pocket Automator's options files only while unchanged`() {
        val home = File("/sdcard/ES-DE")
        val file = "/storage/emulated/0/RetroArch/config/Flycast/Shenmue.opt"
        val mine = mapOf("reicast_alpha_sorting" to "per-pixel (accurate)")
        val userFile = "/storage/emulated/0/RetroArch/config/SwanStation/Tekken.opt"
        val applied = listOf(
            GameSettings.Applied("dreamcast/Shenmue", "flycast.alpha_sorting", file, "per-pixel (accurate)"),
            GameSettings.Applied("psx/Tekken", "swanstation.pgxp", userFile, "true"),
        )
        val script = GameSettings.undoScript(home, applied, null, mapOf(file to mine)) { k -> if (k.key == "swanstation.pgxp") "false" else null }
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(RetroArch.render(mine).toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(script, script.contains("f='$file'; [ \"\$(md5sum \"\$f\" 2>/dev/null | cut -d' ' -f1)\" = $md5 ] && rm -f \"\$f\""))
        // In a file made in RetroArch, the core's own value goes back where Pocket Automator's is still there.
        assertTrue(script, script.contains("sed -i 's|^swanstation_GPU_PGXPEnable = \"true\"\$|swanstation_GPU_PGXPEnable = \"false\"|' \"\$f\""))
        assertFalse(script, script.contains("reicast_alpha_sorting = "))
    }
}
