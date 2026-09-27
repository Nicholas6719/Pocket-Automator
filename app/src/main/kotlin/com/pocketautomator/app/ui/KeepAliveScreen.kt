package com.pocketautomator.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketautomator.app.Background
import com.pocketautomator.app.GuardLink
import com.pocketautomator.app.Shell
import com.pocketautomator.app.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Keep alive: Shizuku started after every restart, and the apps kept running 24/7. */
@Composable
fun KeepAliveScreen(apps: List<InstalledApp>, onAddApps: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = Store.get(context)
    val on by store.keepAliveOn.collectAsStateWithLifecycle()
    val kept by store.keepAlive.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<Background.Status?>(null) }
    var log by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(kept) {
        while (true) {
            val (s, l) = withContext(Dispatchers.IO) {
                runCatching { Background.status(kept) to Background.watchdogLog() }.getOrDefault(null to emptyList())
            }
            status = s
            log = l
            delay(3_000)
        }
    }

    Page(title = "Keep alive", subtitle = "Shizuku and your chosen apps, running 24/7", onBack = onBack) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ToggleCard(
                    title = "Keep apps running",
                    text = "Starts Shizuku after every restart, and checks every 20 seconds that Shizuku, Pocket Automator and the apps below are running, starting any that have stopped (a swipe in Recents, say). Uses the handheld's built-in root service (Retroid and AYN firmware); nothing is rooted or unlocked.",
                    checked = on,
                    onChange = store::setKeepAliveOn,
                )
            }
            item {
                SectionCard {
                    Text("Status", fontWeight = FontWeight.SemiBold)
                    val s = status
                    if (s == null) {
                        Muted("Checking…")
                    } else {
                        StatusLine("Built-in root service", s.root, if (s.root) "available" else "not available on this firmware")
                        StatusLine("Watchdog", s.watchdog, if (s.watchdog) "running" else if (on) "not running" else "off")
                        StatusLine("Shizuku", s.shizuku, if (s.shizuku) "running" else "stopped")
                        val guard = GuardLink.running
                        StatusLine("Restart helper", guard, if (guard) "running (through Shizuku)" else if (on) "not running" else "off")
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Heading("Kept running", Modifier.weight(1f))
                    TextButton(onClick = onAddApps, modifier = Modifier.focusOutline(PillShape)) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Text("Add apps")
                    }
                }
            }
            item {
                SectionCard {
                    Muted("Always: Pocket Automator and Shizuku.")
                }
            }
            if (kept.isEmpty()) item { Muted("Add apps you never want closed (Syncthing, say).", Modifier.padding(horizontal = 4.dp)) }
            items(kept.sortedBy { pkg -> apps.firstOrNull { it.pkg == pkg }?.label?.lowercase() ?: pkg }, key = { it }) { pkg ->
                val app = apps.firstOrNull { it.pkg == pkg }
                val running = status?.apps?.get(pkg)
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp)) {
                            app?.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(app?.label ?: pkg, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(running)
                                Muted(
                                    when {
                                        app == null -> "Not installed"
                                        running == null -> "Checking…"
                                        running -> "Running"
                                        else -> "Stopped; starts again within 20 s"
                                    },
                                    Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                        TextButton(onClick = { store.setKeepAlive(kept - pkg) }, modifier = Modifier.focusOutline(PillShape)) {
                            Text("Remove")
                        }
                    }
                }
            }
            if (log.isNotEmpty()) {
                item { Heading("What the watchdog did") }
                items(log.reversed()) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

/** Choosing apps to keep running. Pocket Automator and Shizuku are always kept, so they aren't offered. */
@Composable
fun KeepAliveAppsScreen(apps: List<InstalledApp>, onBack: () -> Unit) {
    val store = Store.get(LocalContext.current)
    val kept by store.keepAlive.collectAsStateWithLifecycle()
    AppPicker(
        title = "Keep apps running",
        subtitle = "Started again whenever they stop",
        apps = apps.filter { it.pkg != Shell.SHIZUKU_PACKAGE },
        checked = { it.pkg in kept },
        note = { null },
        onToggle = { app, on -> store.setKeepAlive(if (on) kept + app.pkg else kept - app.pkg) },
        onBack = onBack,
    )
}

@Composable
private fun StatusLine(label: String, good: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(good)
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp).weight(1f))
        Muted(text)
    }
}

@Composable
private fun Dot(good: Boolean?) {
    val color = when (good) {
        true -> Good
        false -> Warn
        null -> Color(0xFF6E6E78)
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}
