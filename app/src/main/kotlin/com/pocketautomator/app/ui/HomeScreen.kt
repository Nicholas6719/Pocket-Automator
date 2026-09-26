package com.pocketautomator.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketautomator.app.AutomatorService
import com.pocketautomator.app.Device
import com.pocketautomator.app.GameProfile
import com.pocketautomator.app.Knob
import com.pocketautomator.app.Plan
import com.pocketautomator.app.Profile
import com.pocketautomator.app.Shell
import com.pocketautomator.app.Store
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    shizuku: Shell.Status,
    odinTools: Boolean,
    apps: List<InstalledApp>,
    library: Library,
    onShizuku: () -> Unit,
    onEdit: (Int) -> Unit,
    onOpenGame: (String) -> Unit,
    onGames: () -> Unit,
    onNew: () -> Unit,
    onBackground: () -> Unit,
    onKeepAlive: () -> Unit,
    onDiagnostics: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val store = Store.get(context)
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val enabled by store.enabled.collectAsStateWithLifecycle()
    val now by store.now.collectAsStateWithLifecycle()
    val autoClose by store.autoClose.collectAsStateWithLifecycle()
    val delay by store.autoCloseDelay.collectAsStateWithLifecycle()
    val extras by store.autoCloseApps.collectAsStateWithLifecycle()
    val keepAliveOn by store.keepAliveOn.collectAsStateWithLifecycle()
    val kept by store.keepAlive.collectAsStateWithLifecycle()
    val ready = shizuku == Shell.Status.READY
    // Keeps the banner's modes and fan speed current while the home screen is showing.
    LaunchedEffect(ready) {
        while (ready) {
            AutomatorService.current?.refreshReadingsAsync()
            delay(4_000)
        }
    }
    val games by store.games.collectAsStateWithLifecycle()
    val detection by store.gameDetection.collectAsStateWithLifecycle()
    val openGame = { entry: Library.Entry -> onOpenGame(entry.game.id) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 880.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Hero(now, profiles, apps, library, enabled, ready, onToggle = { store.setEnabled(it) }, onRefresh = onRefresh)
            }
            if (Device.current == Device.Model.UNSUPPORTED) {
                item {
                    Notice("This isn't a Retroid handheld", "Performance, fan and L2/R2 modes are Retroid settings, so they won't do anything here.")
                }
            }
            if (odinTools) {
                item {
                    Notice(
                        "OdinTools is also switching modes",
                        "Its App overrides change performance and fan per app too, and whichever app writes last wins. Turn them off in OdinTools.",
                    )
                }
            }
            if (!ready) item { ShizukuCard(shizuku, onShizuku) }

            if (library.recent.isNotEmpty()) {
                item { Heading("Jump back in") }
                item { GameRow(library.recent, apps, profiles, games, openGame) }
            }
            if (library.size > 0 || games.isNotEmpty()) {
                item {
                    ActionCard(
                        icon = Icons.Rounded.SportsEsports,
                        color = Color(0xFFFFB14D),
                        title = "Games",
                        text = when {
                            !detection -> "Game detection is off · games use their emulator's profile"
                            games.isEmpty() -> "Give a game its own settings, and see how your games run on this chip"
                            games.size == 1 -> "1 game has its own settings · community settings for the rest"
                            else -> "${games.size} games have their own settings · community settings for the rest"
                        },
                        onClick = onGames,
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Heading("Your profiles", Modifier.weight(1f))
                    TextButton(onClick = onNew, modifier = Modifier.focusOutline(PillShape)) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Text("New profile")
                    }
                }
            }
            items(profiles.chunked(2), key = { row -> row.joinToString { it.id.toString() } }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { profile ->
                        ProfileCard(
                            profile = profile,
                            apps = apps,
                            library = library,
                            active = enabled && profile.id == now.profileId,
                            modifier = Modifier.weight(1f),
                            onClick = { onEdit(profile.id) },
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item { Heading("Background") }
            item {
                ActionCard(
                    icon = Icons.Rounded.AutoDelete,
                    color = Color(0xFF7C8CFF),
                    title = "Auto-close",
                    text = if (autoClose) {
                        val count = profiles.count { !it.isDefault && it.apps.isNotEmpty() && it.autoClose } + extras.size
                        "On for $count app${if (count == 1) "" else "s"} · closes them $delay s after you've left them and the screen goes off or another emulator opens. Choose which apps here."
                    } else {
                        "Off · emulators stay open until you close them"
                    },
                    onClick = onBackground,
                )
            }
            item {
                ActionCard(
                    icon = Icons.Rounded.Favorite,
                    color = Color(0xFF52D48A),
                    title = "Keep alive",
                    text = if (keepAliveOn) {
                        val names = kept.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg }?.label }.sorted()
                        "On · Shizuku starts itself after a restart; Pocket Automator" +
                            (if (names.isEmpty()) "" else ", " + names.joinToString()) + " and Shizuku stay running 24/7"
                    } else {
                        "Off · Shizuku has to be started by hand after a restart"
                    },
                    onClick = onKeepAlive,
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onBackup, modifier = Modifier.focusOutline(PillShape)) { Text("Back up") }
                    OutlinedButton(onClick = onRestore, modifier = Modifier.focusOutline(PillShape)) { Text("Restore") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDiagnostics, modifier = Modifier.focusOutline(PillShape)) { Text("Diagnostics") }
                }
            }
        }
    }
}

/** The banner: your latest game's art, what's in front, and the handheld's modes right now. */
@Composable
private fun Hero(
    now: Store.Now,
    profiles: List<Profile>,
    apps: List<InstalledApp>,
    library: Library,
    enabled: Boolean,
    ready: Boolean,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val profile = profiles.firstOrNull { it.id == now.profileId }
    val app = now.app?.let { pkg -> apps.firstOrNull { it.pkg == pkg } }
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().height(200.dp).clip(shape)) {
        // The game you're likely playing now, when an emulator is in front; otherwise your latest.
        val banner = now.app?.let { library.lastOn(it)?.wide } ?: library.banner
        if (banner != null) {
            GameArt(banner, Modifier.fillMaxSize(), maxSide = 960)
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Accent, AccentAlt))))
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0xF0101014), Color(0xB0101014), Color(0x30101014))),
            ),
        )
        Row(Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "POCKET AUTOMATOR · ${Device.current.title.uppercase()}",
                    color = Accent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Text(
                    when {
                        !enabled -> "Automation is off"
                        !ready -> "Waiting for Shizuku"
                        now.game != null && now.app != null -> now.game.name
                        profile == null -> "Getting ready…"
                        else -> profile.name
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = when {
                    !enabled || !ready -> null
                    now.game != null && app != null -> "Playing on ${app.label}" + (profile?.let { " · ${it.name}" } ?: " · its own settings")
                    app != null && profile?.isDefault == false -> "Playing on ${app.label}"
                    app != null -> "In front: ${app.label}"
                    else -> null
                }
                subtitle?.let { Text(it, color = Color(0xFFD0D0DA), style = MaterialTheme.typography.bodyMedium) }
                now.readings?.values?.let { v ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        v[Knob.PERFORMANCE]?.let { Badge(performanceLook(it)) }
                        v[Knob.FAN]?.let { fan ->
                            val look = fanLook(fan)
                            Row(
                                Modifier.background(look.color.copy(alpha = 0.18f), PillShape)
                                    .border(1.dp, look.color.copy(alpha = 0.45f), PillShape)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                SpinningFan(fan, look.color, Modifier.size(15.dp))
                                val rpm = now.readings.fanRpm?.takeIf { it > 0 }?.let { " · $it rpm" }.orEmpty()
                                Text("${look.label} fan$rpm", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onRefresh, modifier = Modifier.focusOutline(PillShape)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                    Text(if (enabled) "On" else "Off", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggle,
                        modifier = Modifier.focusOutline(PillShape),
                        colors = SwitchDefaults.colors(checkedTrackColor = Accent),
                    )
                }
                app?.icon?.let {
                    Image(it, contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
                }
            }
        }
    }
    if (app == null && now.app != null && enabled && ready) {
        Muted("In front: ${InstalledApps.label(context, now.app)}", Modifier.padding(start = 8.dp))
    }
}

/** A row of covers; each opens the game's page. */
@Composable
private fun GameRow(
    games: List<Library.Entry>,
    apps: List<InstalledApp>,
    profiles: List<Profile>,
    own: List<GameProfile>,
    onOpen: (Library.Entry) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(games, key = { it.game.id }) { entry ->
            val mode = own.firstOrNull { it.id == entry.game.id }?.settings?.get(Knob.PERFORMANCE)
                ?: Plan.profileFor(profiles, entry.emulator).settings[Knob.PERFORMANCE]
            GameCover(entry, apps.firstOrNull { it.pkg == entry.emulator }, mode, onClick = { onOpen(entry) })
        }
    }
}

@Composable
private fun GameCover(entry: Library.Entry, emulator: InstalledApp?, mode: Int?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.width(108.dp).focusOutline(shape).clickable(onClick = onClick)) {
        Box(Modifier.width(108.dp).height(150.dp).clip(shape)) {
            GameArt(entry.cover, Modifier.fillMaxSize(), placeholder = emulator?.let { Color(it.color) } ?: Color(0xFF2A2A31))
            if (entry.cover == null) {
                Text(
                    entry.game.name,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 4,
                )
            }
            emulator?.icon?.let {
                Image(
                    it,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(24.dp).clip(RoundedCornerShape(7.dp)),
                )
            }
            // How this game will run: its emulator's performance mode.
            mode?.let {
                val look = performanceLook(it)
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                        .background(Color(0xCC141418), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(look.icon, contentDescription = look.label, tint = look.color, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(
            entry.game.name,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    apps: List<InstalledApp>,
    library: Library,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val linked = profile.apps.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg } }
    val lead = linked.firstOrNull()
    val color = when {
        profile.isDefault -> AccentAlt
        lead != null -> Color(lead.color)
        else -> Accent
    }
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .height(118.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.55f), Color(0xFF1E1E24), Color(0xFF1A1A1F))))
            .border(if (active) 2.dp else 1.dp, if (active) Good else color.copy(alpha = 0.35f), shape)
            .focusOutline(shape)
            .clickable(onClick = onClick),
    ) {
        val covers = lead?.let { library.forEmulator(it.pkg, 2) }.orEmpty()
        Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x33000000)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    profile.isDefault -> Icon(Icons.Rounded.Home, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                    lead?.icon != null -> Image(lead.icon, contentDescription = null, modifier = Modifier.size(52.dp))
                    else -> Icon(Icons.Rounded.VideogameAsset, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (active) {
                        Text(
                            "IN USE",
                            modifier = Modifier.padding(start = 8.dp).background(Good.copy(alpha = 0.2f), PillShape).padding(horizontal = 8.dp, vertical = 2.dp),
                            color = Good,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                ModeBadges(profile.settings, compact = true)
                val last = lead?.let { library.lastOn(it.pkg) }
                val line = when {
                    profile.isDefault -> "Home, ES-DE and everything else"
                    profile.apps.isEmpty() -> "No apps linked yet"
                    linked.size > 1 -> linked.joinToString { it.label }
                    last != null -> "Last played: ${last.game.name}"
                    else -> null
                }
                line?.let { Muted(it, maxLines = 1) }
            }
            // Covers of this emulator's games, fanned out at the right.
            if (covers.isNotEmpty()) {
                Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy((-22).dp)) {
                    covers.forEachIndexed { i, entry ->
                        GameArt(
                            entry.cover,
                            Modifier.offset(y = (if (i % 2 == 0) -4 else 4).dp).width(50.dp).height(72.dp)
                                .clip(RoundedCornerShape(8.dp)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(8.dp)),
                            maxSide = 200,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, title: String, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.35f), Color(0xFF1E1E24))))
            .focusOutline(shape).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(34.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Muted(text)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun Notice(title: String, text: String) {
    SectionCard(border = Warn) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Muted(text)
    }
}

@Composable
private fun ShizukuCard(status: Shell.Status, onShizuku: () -> Unit) {
    SectionCard(border = Warn) {
        Text(
            when (status) {
                Shell.Status.NOT_INSTALLED -> "Shizuku is needed"
                Shell.Status.NOT_RUNNING -> "Shizuku isn't running"
                Shell.Status.NO_PERMISSION -> "Allow Pocket Automator in Shizuku"
                Shell.Status.READY -> ""
            },
            fontWeight = FontWeight.SemiBold,
        )
        Muted(
            when (status) {
                Shell.Status.NOT_INSTALLED ->
                    "Shizuku lets Pocket Automator change the performance and fan modes without root. Install it from Google Play or its GitHub releases."
                Shell.Status.NOT_RUNNING ->
                    "With Keep alive on, Pocket Automator starts it by itself within a few seconds. Otherwise open Shizuku and start it."
                else -> "Pocket Automator asks Shizuku for access once."
            },
        )
        Button(onClick = onShizuku, modifier = Modifier.focusOutline(PillShape)) {
            Text(
                when (status) {
                    Shell.Status.NOT_INSTALLED -> "Get Shizuku"
                    Shell.Status.NOT_RUNNING -> "Open Shizuku"
                    else -> "Allow access"
                },
            )
        }
    }
}

@Composable
private fun Muted(text: String, maxLines: Int) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "High Performance · Smart fan · Wi-Fi off", or what Default does with nothing set. */
fun summary(profile: Profile): String {
    if (profile.settings.isEmpty()) return "Changes nothing"
    return Knob.entries.mapNotNull { knob ->
        val value = profile.settings[knob] ?: return@mapNotNull null
        when (knob) {
            Knob.PERFORMANCE -> Device.describe(knob, value)
            Knob.FAN -> "${Device.describe(knob, value)} fan"
            else -> "${Device.title(knob)} ${Device.describe(knob, value)}"
        }
    }.joinToString(" · ")
}
