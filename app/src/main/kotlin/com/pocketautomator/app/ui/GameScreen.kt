package com.pocketautomator.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketautomator.app.Device
import com.pocketautomator.app.EmuKnob
import com.pocketautomator.app.Emulator
import com.pocketautomator.app.EsDe
import com.pocketautomator.app.GameProfile
import com.pocketautomator.app.GameSettings
import com.pocketautomator.app.Hooks
import com.pocketautomator.app.Knob
import com.pocketautomator.app.Plan
import com.pocketautomator.app.Shell
import com.pocketautomator.app.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One game's own settings: the handheld's, on top of its emulator's profile, and some of the emulator's. */
@Composable
fun GameScreen(
    id: String,
    apps: List<InstalledApp>,
    library: Library,
    knobs: List<Knob>,
    refreshRates: List<Int>,
    onOpenProfile: (Int) -> Unit,
    onGames: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = Store.get(context)
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val games by store.games.collectAsStateWithLifecycle()
    val status by store.gameStatus.collectAsStateWithLifecycle()
    val detection by store.gameDetection.collectAsStateWithLifecycle()
    val entry = library.find(id)
    val saved = games.firstOrNull { it.id == id }
    val base = saved ?: entry?.let { GameProfile.of(it.game) } ?: run { onBack(); return }
    val emulatorApp = entry?.emulator?.let { pkg -> apps.firstOrNull { it.pkg == pkg } }
    val profile = Plan.profileFor(profiles, entry?.emulator)
    val emulator = Emulator.of(entry?.emulator)
    var resetting by remember { mutableStateOf(false) }
    val same = Look(Icons.Rounded.Link, Color(0xFF8E8E99), "Same as profile", profile.name)
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 880.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { GameHeader(base, entry, emulatorApp, onBack) }
            if (!detection) {
                item {
                    SectionCard(border = Warn) {
                        Text("Game detection is off", fontWeight = FontWeight.SemiBold)
                        Muted("Without it, Pocket Automator can't tell which game is running, so these settings wait. Turn it on on the Games page.")
                        OutlinedButton(onClick = onGames, modifier = Modifier.focusOutline(PillShape)) { Text("Games page") }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Heading("Handheld", Modifier.weight(1f))
                    TextButton(onClick = { onOpenProfile(profile.id) }, modifier = Modifier.focusOutline(PillShape)) {
                        Text("${profile.name} profile")
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    }
                }
            }
            item {
                Muted(
                    "Played from ES-DE, this game uses the ${profile.name} profile with anything set here on top.",
                    Modifier.padding(horizontal = 4.dp),
                )
            }
            item { Heading("Performance mode") }
            item {
                TileRow(
                    current = base.settings[Knob.PERFORMANCE],
                    options = Device.PERFORMANCE.map { performanceLook(it.value) to it.value },
                    none = same.copy(hint = describe(profile.settings[Knob.PERFORMANCE], Knob.PERFORMANCE)),
                    onPick = { v -> store.editGame(base) { it.copy(settings = it.settings.with(Knob.PERFORMANCE, v)) } },
                )
            }
            item { Heading("Fan") }
            item {
                TileRow(
                    current = base.settings[Knob.FAN],
                    options = Device.FAN.map { fanLook(it.value) to it.value },
                    none = same.copy(hint = describe(profile.settings[Knob.FAN], Knob.FAN)),
                    onPick = { v -> store.editGame(base) { it.copy(settings = it.settings.with(Knob.FAN, v)) } },
                )
            }
            items(knobs.filter { it != Knob.PERFORMANCE && it != Knob.FAN }, key = { it.key }) { knob ->
                KnobCard(knob, base.settings[knob], refreshRates, none = "Same as profile") { v ->
                    store.editGame(base) { it.copy(settings = it.settings.with(knob, v)) }
                }
            }

            item { Heading(emulator?.let { "${it.title} settings" } ?: "Emulator settings") }
            if (emulator == null) {
                item {
                    Muted(
                        (emulatorApp?.label ?: "This emulator") + " keeps its own settings for every game. Per-game emulator settings work with Eden, Dolphin and Azahar.",
                        Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                item { EmulatorSettings(emulator, base, status, onPick = { key, v -> store.editGame(base) { it.copy(emu = it.emu.with(key, v)) } }) }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Spacer(Modifier.weight(1f))
                    if (saved != null) {
                        TextButton(onClick = { resetting = true }, modifier = Modifier.focusOutline(PillShape)) {
                            Text("Reset this game", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (resetting) {
        AlertDialog(
            onDismissRequest = { resetting = false },
            title = { Text("Reset ${base.name}?") },
            text = { Text("It goes back to the ${profile.name} profile and ${emulator?.title ?: "its emulator"}'s own settings. Anything you set for it in the emulator itself stays.") },
            confirmButton = {
                TextButton(onClick = { resetting = false; store.deleteGame(base.id) }, modifier = Modifier.focusOutline(PillShape)) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { resetting = false }, modifier = Modifier.focusOutline(PillShape)) { Text("Cancel") }
            },
        )
    }
}

private fun <K, V> Map<K, V>.with(key: K, value: V?): Map<K, V> = if (value == null) this - key else this + (key to value)

private fun describe(value: Int?, knob: Knob) = value?.let { Device.describe(knob, it) } ?: "Not set"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmulatorSettings(emulator: Emulator, game: GameProfile, status: Map<String, String>, onPick: (String, String?) -> Unit) {
    val context = LocalContext.current
    val store = Store.get(context)
    val needsShizuku = emulator != Emulator.AZAHAR
    val inspection by produceState<GameSettings.Inspection?>(null, emulator) {
        value = withContext(Dispatchers.IO) {
            if (needsShizuku && !Shell.ready) null
            else runCatching {
                val azahar = if (emulator == Emulator.AZAHAR) store.azaharConfig ?: GameSettings.findAzaharConfig()?.also { store.azaharConfig = it } else null
                GameSettings.inspect(emulator, azahar)
            }.getOrNull()
        }
    }
    val found = inspection
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Muted(
            when (emulator) {
                Emulator.EDEN -> "Written to Eden's own per-game settings for this game. Anything you've set for it in Eden itself stays as you set it."
                Emulator.DOLPHIN -> "Written to Dolphin's own per-game settings for this game. Anything you've set for it in Dolphin itself stays as you set it."
                Emulator.AZAHAR -> "Azahar has no per-game settings, so this is swapped in as the game starts from ES-DE, and Azahar's own comes back once you're back in ES-DE or Azahar closes."
            },
            Modifier.padding(horizontal = 4.dp),
        )
        if (found == null) {
            SectionCard { Muted(if (needsShizuku && !Shell.ready) "Waiting for Shizuku to read ${emulator.title}'s settings…" else "Reading ${emulator.title}'s settings…") }
            return@Column
        }
        found.knobs.forEach { knob ->
            EmuKnobCard(knob, game.emu[knob.key], found.global[knob.key], status["${game.id}|${knob.key}"]) { onPick(knob.key, it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmuKnobCard(knob: EmuKnob, value: String?, global: String?, problem: String?, onPick: (String?) -> Unit) {
    SectionCard(border = problem?.let { Warn }) {
        Text(knob.title, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val globalLabel = knob.label(global) ?: global?.takeIf { it.isNotEmpty() }?.substringAfterLast('/')
            Choice("Same as ${knob.emulator.title}" + (globalLabel?.let { " ($it)" } ?: ""), value == null) { onPick(null) }
            knob.options.forEach { option -> Choice(option.label, value == option.value) { onPick(option.value) } }
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
    }
}

@Composable
private fun GameHeader(game: GameProfile, entry: Library.Entry?, emulator: InstalledApp?, onBack: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().height(170.dp).clip(shape)) {
        val wide = entry?.wide
        if (wide != null) {
            GameArt(wide, Modifier.fillMaxSize(), maxSide = 960)
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf((emulator?.let { Color(it.color) } ?: AccentAlt).copy(alpha = 0.8f), Color(0xFF1C1C22)))))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF0101014), Color(0xA0101014), Color(0x30101014)))))
        Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.focusOutline(PillShape)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            GameArt(entry?.cover, Modifier.padding(start = 6.dp).width(96.dp).height(134.dp).clip(RoundedCornerShape(12.dp)), maxSide = 300)
            Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(game.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    emulator?.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))) }
                    Text(
                        listOfNotNull(emulator?.label, game.system.uppercase()).joinToString(" · "),
                        color = Color(0xFFD0D0DA),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                ModeBadges(game.settings)
            }
        }
    }
}

/** Every game's own settings, and the link from ES-DE that makes them work. */
@Composable
fun GamesScreen(apps: List<InstalledApp>, library: Library, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = Store.get(context)
    val games by store.games.collectAsStateWithLifecycle()
    val detection by store.gameDetection.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var undoing by remember { mutableStateOf(false) }
    var enabling by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val hook by produceState<HookState?>(null, detection, refresh) {
        value = withContext(Dispatchers.IO) {
            val home = EsDe.home()
            HookState(home != null, home?.let { Hooks.installed(it) } == true, home?.let { Hooks.enabledInEsDe(it) } == true, home?.let { Hooks.folder(it).path })
        }
    }
    LaunchedEffect(detection) {
        // The service installs the hook; look again once it's had a moment.
        kotlinx.coroutines.delay(1500)
        refresh++
    }

    Page(title = "Games", subtitle = "Settings of their own, for games started from ES-DE", onBack = onBack) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ToggleCard(
                    title = "Detect games from ES-DE",
                    text = "ES-DE tells Pocket Automator which game it starts (through its custom event scripts), so each game can have its own settings. Nothing changes in how you use ES-DE.",
                    checked = detection,
                    onChange = store::setGameDetection,
                )
            }
            hook?.let { h ->
                if (detection) {
                    item {
                        SectionCard(border = if (h.esde && h.installed && h.enabled) null else Warn) {
                            GameStatusLine("ES-DE", h.esde, if (h.esde) "found" else "not found (or Pocket Automator can't read storage yet)")
                            GameStatusLine("Game hook", h.installed, if (h.installed) "installed" else "not installed yet")
                            GameStatusLine("ES-DE's custom event scripts", h.enabled, if (h.enabled) "on" else "off")
                            if (h.esde && !h.enabled) {
                                Muted("ES-DE only runs the hook with this on: in ES-DE, Menu → Other settings → Enable custom event scripts. Or let Pocket Automator turn it on (ES-DE restarts).")
                                Button(onClick = { enabling = true }, modifier = Modifier.focusOutline(PillShape)) { Text("Turn it on") }
                            }
                        }
                    }
                }
            }

            item { Heading("Games with their own settings") }
            if (games.isEmpty()) {
                item { Muted("None yet. Pick a game below, or tap one in Jump back in.", Modifier.padding(horizontal = 4.dp)) }
            }
            items(games.sortedBy { it.name.lowercase() }, key = { "g" + it.id }) { game ->
                GameLine(game.name, library.find(game.id), apps, summaryOf(game)) { onOpen(game.id) }
            }

            if (library.recent.isNotEmpty()) {
                item { Heading("Recently played") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(library.recent, key = { it.game.id }) { entry ->
                            val shape = RoundedCornerShape(12.dp)
                            GameArt(
                                entry.cover,
                                Modifier.width(84.dp).height(118.dp).clip(shape).focusOutline(shape).clickable { onOpen(entry.game.id) },
                                maxSide = 260,
                            )
                        }
                    }
                }
            }

            item { Heading("Find a game") }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotBlank()) {
                val found = library.games.filter { it.game.name.contains(query.trim(), ignoreCase = true) }.take(30)
                if (found.isEmpty()) item { Muted("No game by that name in ES-DE.", Modifier.padding(horizontal = 4.dp)) }
                items(found, key = { "f" + it.game.id }) { entry ->
                    GameLine(entry.game.name, entry, apps, games.firstOrNull { it.id == entry.game.id }?.let(::summaryOf)) { onOpen(entry.game.id) }
                }
            }

            item { Heading("Undo") }
            item {
                SectionCard {
                    Muted(
                        "\"Reset this game\" on a game's page takes back its settings. This takes back every emulator change at once; games keep their handheld settings." +
                            (hook?.folder?.let { "\n\nWithout the app: run \"${Hooks.UNDO}\" in $it from Retroid's settings (Run script as Root). Copies of the emulators' files from before are in its backups folder." } ?: ""),
                    )
                    OutlinedButton(onClick = { undoing = true }, modifier = Modifier.focusOutline(PillShape)) { Text("Undo all emulator changes") }
                }
            }
        }
    }

    if (undoing) {
        AlertDialog(
            onDismissRequest = { undoing = false },
            title = { Text("Undo all emulator changes?") },
            text = { Text("Every game goes back to its emulators' own settings. Anything you set in the emulators themselves stays.") },
            confirmButton = {
                TextButton(onClick = {
                    undoing = false
                    store.replaceGames(store.games.value.map { it.copy(emu = emptyMap()) })
                }, modifier = Modifier.focusOutline(PillShape)) { Text("Undo", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { undoing = false }, modifier = Modifier.focusOutline(PillShape)) { Text("Cancel") } },
        )
    }
    if (enabling) {
        AlertDialog(
            onDismissRequest = { enabling = false },
            title = { Text("Turn on ES-DE's custom event scripts?") },
            text = { Text("Pocket Automator changes the setting and closes ES-DE so it picks it up. Press Home and ES-DE opens again, where you left it.") },
            confirmButton = {
                TextButton(onClick = {
                    enabling = false
                    scope.launch {
                        withContext(Dispatchers.IO) { EsDe.home()?.let { Hooks.enableInEsDe(it) } }
                        refresh++
                    }
                }, modifier = Modifier.focusOutline(PillShape)) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { enabling = false }, modifier = Modifier.focusOutline(PillShape)) { Text("Cancel") } },
        )
    }
}

private data class HookState(val esde: Boolean, val installed: Boolean, val enabled: Boolean, val folder: String?)

private fun summaryOf(game: GameProfile): String {
    val parts = mutableListOf<String>()
    game.settings[Knob.PERFORMANCE]?.let { parts += Device.describe(Knob.PERFORMANCE, it) }
    game.settings[Knob.FAN]?.let { parts += "${Device.describe(Knob.FAN, it)} fan" }
    val others = game.settings.keys.count { it != Knob.PERFORMANCE && it != Knob.FAN }
    if (others > 0) parts += "$others more"
    game.emu.forEach { (key, value) ->
        val knob = com.pocketautomator.app.EmuKnobs.byKey(key) ?: return@forEach
        parts += "${knob.title} ${knob.label(value) ?: value.substringAfterLast('/')}"
    }
    return parts.joinToString(" · ").ifEmpty { "Nothing set" }
}

@Composable
private fun GameLine(name: String, entry: Library.Entry?, apps: List<InstalledApp>, summary: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color(0xFF1E1E24)).focusOutline(shape).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GameArt(entry?.cover, Modifier.width(40.dp).height(56.dp).clip(RoundedCornerShape(6.dp)), maxSide = 140)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            summary?.let { Muted(it) }
        }
        entry?.emulator?.let { pkg -> apps.firstOrNull { it.pkg == pkg } }?.icon?.let {
            Image(it, contentDescription = null, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)))
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun GameStatusLine(label: String, good: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (good) Good else Warn, RoundedCornerShape(50)))
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp).weight(1f))
        Muted(text)
    }
}
