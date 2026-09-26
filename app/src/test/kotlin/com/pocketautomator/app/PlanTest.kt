package com.pocketautomator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {

    private val default = mapOf(Knob.PERFORMANCE to Knob.PERF_PERFORMANCE, Knob.FAN to Knob.FAN_SMART)
    private val heavy = mapOf(Knob.PERFORMANCE to Knob.PERF_HIGH, Knob.FAN to Knob.FAN_SMART)
    private val device = mapOf(
        Knob.PERFORMANCE to Knob.PERF_PERFORMANCE,
        Knob.FAN to Knob.FAN_SMART,
        Knob.WIFI to 1,
        Knob.TRIGGERS to Knob.TRIGGERS_BOTH,
    )

    @Test
    fun `a game profile writes only what differs, plus the fan after a performance change`() {
        val step = Plan.switchTo(heavy, default, device, emptyMap())
        assertEquals(mapOf(Knob.PERFORMANCE to Knob.PERF_HIGH, Knob.FAN to Knob.FAN_SMART), step.writes)
        // Default sets both of these, so there is nothing to remember.
        assertEquals(emptyMap<Knob, Int>(), step.snapshot)
    }

    @Test
    fun `leaving a game goes back to Default`() {
        val inGame = device + (Knob.PERFORMANCE to Knob.PERF_HIGH)
        val step = Plan.switchTo(default, default, inGame, emptyMap())
        assertEquals(mapOf(Knob.PERFORMANCE to Knob.PERF_PERFORMANCE, Knob.FAN to Knob.FAN_SMART), step.writes)
    }

    @Test
    fun `a performance change keeps the fan it had when no profile sets one`() {
        val step = Plan.switchTo(mapOf(Knob.PERFORMANCE to Knob.PERF_STANDARD), emptyMap(), device + (Knob.FAN to Knob.FAN_SPORT), emptyMap())
        assertEquals(mapOf(Knob.PERFORMANCE to Knob.PERF_STANDARD, Knob.FAN to Knob.FAN_SPORT), step.writes)
    }

    @Test
    fun `no performance change, no fan write`() {
        val step = Plan.switchTo(mapOf(Knob.WIFI to 0), default, device, emptyMap())
        assertEquals(mapOf(Knob.WIFI to 0), step.writes)
    }

    @Test
    fun `something Default leaves alone is put back as it was`() {
        val quiet = heavy + (Knob.WIFI to 0)
        val entering = Plan.switchTo(quiet, default, device, emptyMap())
        assertEquals(0, entering.writes[Knob.WIFI])
        assertEquals(mapOf(Knob.WIFI to 1), entering.snapshot)

        val inGame = device + entering.writes
        val leaving = Plan.switchTo(default, default, inGame, entering.snapshot)
        assertEquals(mapOf(Knob.PERFORMANCE to Knob.PERF_PERFORMANCE, Knob.FAN to Knob.FAN_SMART, Knob.WIFI to 1), leaving.writes)
        assertTrue(leaving.snapshot.isEmpty())
    }

    @Test
    fun `the snapshot keeps the first value across game to game switches`() {
        val a = mapOf(Knob.WIFI to 0)
        val b = mapOf(Knob.WIFI to 0, Knob.BLUETOOTH to 0)
        val first = Plan.switchTo(a, default, device, emptyMap())
        val afterA = device + first.writes
        val second = Plan.switchTo(b, default, afterA + (Knob.BLUETOOTH to 1), first.snapshot)
        // Wi-Fi is already off in game A: the snapshot still says it was on before any game.
        assertEquals(mapOf(Knob.WIFI to 1, Knob.BLUETOOTH to 1), second.snapshot)
        assertEquals(mapOf(Knob.BLUETOOTH to 0), second.writes)
    }

    @Test
    fun `a game that doesn't set something restores what the last game changed`() {
        val a = mapOf(Knob.WIFI to 0)
        val first = Plan.switchTo(a, default, device, emptyMap())
        val step = Plan.switchTo(heavy, default, device + first.writes, first.snapshot)
        assertEquals(1, step.writes[Knob.WIFI])
        assertTrue(Knob.WIFI !in step.snapshot)
    }

    @Test
    fun `Default taking over a knob drops it from the snapshot`() {
        val step = Plan.switchTo(default + (Knob.WIFI to 1), default + (Knob.WIFI to 1), device + (Knob.WIFI to 0), mapOf(Knob.WIFI to 0))
        assertEquals(1, step.writes[Knob.WIFI])
        assertTrue(step.snapshot.isEmpty())
    }

    @Test
    fun `an unreadable value is written anyway and not remembered`() {
        val step = Plan.switchTo(mapOf(Knob.DND to 1), emptyMap(), emptyMap(), emptyMap())
        assertEquals(mapOf(Knob.DND to 1), step.writes)
        assertTrue(step.snapshot.isEmpty())
    }

    @Test
    fun `game screen is never written or remembered`() {
        val step = Plan.switchTo(mapOf(Knob.GAME_SCREEN to Knob.SCREEN_BOTTOM), emptyMap(), device, emptyMap())
        assertTrue(step.writes.isEmpty())
        assertTrue(step.snapshot.isEmpty())
    }

    @Test
    fun `apps find their profile, and anything else gets Default`() {
        val profiles = listOf(
            Profile(0, "Default", default),
            Profile(1, "Heavy", heavy, setOf("com.armsx2")),
            Profile(2, "Retro", emptyMap(), setOf("com.retroarch.aarch64")),
        )
        assertEquals(1, Plan.profileFor(profiles, "com.armsx2").id)
        assertEquals(2, Plan.profileFor(profiles, "com.retroarch.aarch64").id)
        assertEquals(0, Plan.profileFor(profiles, "com.android.launcher").id)
        assertEquals(0, Plan.profileFor(profiles, null).id)
    }

    @Test
    fun `linking an app takes it from any other profile`() {
        val profiles = listOf(
            Profile(0, "Default"),
            Profile(1, "Heavy", apps = setOf("a", "b")),
            Profile(2, "Retro", apps = setOf("c")),
        )
        val after = Plan.relinked(profiles, 2, setOf("b", "c"))
        assertEquals(setOf("a"), after[1].apps)
        assertEquals(setOf("b", "c"), after[2].apps)
        // Default never holds apps.
        assertTrue(Plan.relinked(profiles, 0, setOf("x"))[0].apps.isEmpty())
    }

    @Test
    fun `names stay unique`() {
        val profiles = listOf(Profile(0, "Default"), Profile(1, "Heavy"), Profile(2, "Heavy 2"))
        assertEquals("heavy 3", Plan.uniqueName(profiles, "heavy"))
        assertEquals("Heavy", Plan.uniqueName(profiles, "Heavy", except = 1))
        assertEquals("Profile", Plan.uniqueName(profiles, "   "))
    }
}
