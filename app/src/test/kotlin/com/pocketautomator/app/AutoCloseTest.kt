package com.pocketautomator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCloseTest {

    private val azahar = "org.azahar_emu.azahar"
    private val dolphin = "org.dolphinemu.dolphinemu"
    private val esde = "org.es_de.frontend"
    private val syncthing = "com.github.catfriend1.syncthingfork"
    private val emulators = setOf(azahar, dolphin, "com.retroarch.aarch64")

    private fun closer(closable: (String) -> Boolean = { true }) =
        AutoClose(delayMs = { 10_000 }, isEmulator = { it in emulators }, closable = closable)

    @Test
    fun `quit to ES-DE then sleep closes the emulator after 10 seconds`() {
        val c = closer()
        c.onFront(azahar, 0)
        c.onFront(esde, 1_000)
        assertNull(c.next())
        c.onScreenOff(5_000)
        assertEquals(15_000L, c.next())
        assertTrue(c.due(14_999).isEmpty())
        assertEquals(listOf(azahar), c.due(15_000))
        assertNull(c.next())
    }

    @Test
    fun `sleeping with the emulator in front keeps it`() {
        val c = closer()
        c.onFront(esde, 0)
        c.onFront(azahar, 1_000)
        c.onScreenOff(5_000)
        assertNull(c.next())
        assertTrue(c.due(60_000).isEmpty())
    }

    @Test
    fun `opening another emulator closes the one left behind`() {
        val c = closer()
        c.onFront(azahar, 0)
        c.onFront(esde, 1_000)
        c.onFront(dolphin, 2_000)
        assertEquals(mapOf(azahar to 12_000L), c.waiting)
        assertEquals(listOf(azahar), c.due(12_000))
    }

    @Test
    fun `switching straight from one emulator to another closes the first`() {
        val c = closer()
        c.onFront(azahar, 0)
        c.onFront(dolphin, 1_000)
        assertEquals(listOf(azahar), c.due(11_000))
    }

    @Test
    fun `browsing ES-DE alone never closes anything`() {
        val c = closer()
        c.onFront(azahar, 0)
        c.onFront(esde, 1_000)
        c.onFront("com.brave.browser", 2_000)
        c.onFront(esde, 3_000)
        assertNull(c.next())
        assertEquals(setOf(azahar), c.leftBehind)
    }

    @Test
    fun `coming back in time cancels the close`() {
        val c = closer()
        c.onFront(azahar, 0)
        c.onFront(esde, 1_000)
        c.onScreenOff(2_000)
        c.onFront(azahar, 8_000)
        assertNull(c.next())
        assertTrue(c.due(20_000).isEmpty())
        assertTrue(c.leftBehind.isEmpty())
    }

    @Test
    fun `the emulator just opened is never closed, even when it was waiting`() {
        val c = closer()
        c.onFront(azahar, 0)
        c.onFront(esde, 1_000)
        c.onScreenOff(2_000)
        // Woken and straight back to Azahar through ES-DE, then Dolphin: only Azahar was left.
        c.onFront(esde, 4_000)
        c.onFront(dolphin, 5_000)
        assertEquals(listOf(azahar), c.due(20_000))
    }

    @Test
    fun `apps that aren't emulators and profiles that opt out are never closed`() {
        val c = closer(closable = { it != dolphin })
        c.onFront(syncthing, 0)
        c.onFront(dolphin, 1_000)
        c.onFront(esde, 2_000)
        c.onScreenOff(3_000)
        assertNull(c.next())
        assertTrue(syncthing !in c.leftBehind)
    }

    @Test
    fun `a second trigger doesn't push the close back`() {
        val c = closer()
        c.onFront(azahar, 0)
        c.onFront(esde, 1_000)
        c.onScreenOff(2_000)
        c.onScreenOff(9_000)
        assertEquals(12_000L, c.next())
    }

    @Test
    fun `close commands stop the app before clearing its Recents cards`() {
        assertEquals(
            "am force-stop $azahar; am stack remove 12; am stack remove 15",
            Commands.close(azahar, listOf(12, 15)),
        )
        assertEquals("am force-stop $azahar", Commands.close(azahar, emptyList()))
    }
}

class EsDeTest {

    private val xml = """<?xml version="1.0"?>
<alternativeEmulator>
	<label>Azahar (Standalone)</label>
</alternativeEmulator>
<gameList>
	<game>
		<path>./Luigi&apos;s Mansion (USA).3ds</path>
		<name>Luigi&apos;s Mansion</name>
		<favorite>true</favorite>
		<playcount>7</playcount>
		<lastplayed>20260926T123436</lastplayed>
	</game>
	<game>
		<path>./Sub/Animal Crossing - New Leaf .cci</path>
		<name>Animal Crossing : New Leaf</name>
		<altemulator>Citra (Standalone)</altemulator>
	</game>
	<folder><path>./Sub</path></folder>
</gameList>"""

    @Test
    fun `gamelists are read`() {
        val games = EsDe.parse("n3ds", xml)
        assertEquals(2, games.size)
        val luigi = games[0]
        assertEquals("Luigi's Mansion", luigi.name)
        assertEquals("Luigi's Mansion (USA)", luigi.file)
        assertTrue(luigi.favorite)
        assertEquals(7, luigi.playCount)
        assertTrue(luigi.lastPlayed > 0)
        assertEquals("Azahar (Standalone)", luigi.emulatorLabel)
        assertEquals("Animal Crossing - New Leaf ", games[1].file)
        assertEquals("Citra (Standalone)", games[1].emulatorLabel)
        assertEquals(0L, games[1].lastPlayed)
    }

    private val installed = setOf(
        "org.azahar_emu.azahar", "com.retroarch.aarch64", "dev.eden.eden_emulator",
        "xyz.aethersx2.android", "com.armsx2", "me.magnum.melonds.nightly", "org.dolphinemu.dolphinemu",
    )

    private fun game(system: String, label: String?) = EsDe.Game(system, "x", "x", false, 0, 0, label)

    @Test
    fun `games find their emulator`() {
        assertEquals("org.azahar_emu.azahar", EsDe.emulatorFor(game("n3ds", "Azahar (Standalone)"), installed))
        // Citra isn't installed: Azahar is its successor.
        assertEquals("org.azahar_emu.azahar", EsDe.emulatorFor(game("n3ds", "Citra (Standalone)"), installed))
        // The Switch label ES-DE shows for a custom Eden entry.
        assertEquals("dev.eden.eden_emulator", EsDe.emulatorFor(game("switch", "Yuzu EA (Standalone)"), installed))
        assertEquals("com.armsx2", EsDe.emulatorFor(game("ps2", "ARMSX2 (Standalone)"), installed))
        // A RetroArch core.
        assertEquals("com.retroarch.aarch64", EsDe.emulatorFor(game("gba", "mGBA"), installed))
        // No label: the system's usual emulator.
        assertEquals("org.dolphinemu.dolphinemu", EsDe.emulatorFor(game("gc", null), installed))
        assertEquals("me.magnum.melonds.nightly", EsDe.emulatorFor(game("nds", null), installed))
        assertEquals("com.retroarch.aarch64", EsDe.emulatorFor(game("snes", null), installed))
        assertNull(EsDe.emulatorFor(game("androidgames", null), installed))
    }

    @Test
    fun `recent and favorites`() {
        val a = EsDe.Game("gba", "A", "a", true, 100, 1, null)
        val b = EsDe.Game("gba", "B", "b", false, 300, 1, null)
        val c = EsDe.Game("gba", "C", "c", true, 0, 0, null)
        assertEquals(listOf(b, a), EsDe.recent(listOf(a, b, c), 5))
        assertEquals(listOf(a, c), EsDe.favorites(listOf(a, b, c)))
    }
}
