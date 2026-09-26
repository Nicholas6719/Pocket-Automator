package com.pocketautomator.app.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketautomator.app.AutomatorService
import com.pocketautomator.app.Store
import kotlinx.coroutines.delay

/** Auto-close: the switch, the delay, and which emulators it applies to. */
@Composable
fun BackgroundScreen(apps: List<InstalledApp>, onAddApps: () -> Unit, onBack: () -> Unit) {
    val store = Store.get(LocalContext.current)
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val on by store.autoClose.collectAsStateWithLifecycle()
    val seconds by store.autoCloseDelay.collectAsStateWithLifecycle()
    val onHome by store.closeOnHome.collectAsStateWithLifecycle()
    val extras by store.autoCloseApps.collectAsStateWithLifecycle()
    var waiting by remember { mutableStateOf(emptyMap<String, Long>()) }
    LaunchedEffect(Unit) {
        while (true) {
            waiting = AutomatorService.current?.waitingToClose().orEmpty()
            delay(1000)
        }
    }

    Page(title = "Auto-close", subtitle = "Closing emulators you're done with", onBack = onBack) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ToggleCard(
                    title = "Auto-close emulators you've left",
                    text = "Once you leave an emulator, it closes $seconds seconds after " +
                        (if (onHome) "you're back in ES-DE, " else "") +
                        "the screen goes off, or another emulator opens. Quit a game and its card leaves Recents within a few seconds. " +
                        "Sleeping mid-game never closes the game you're in, and coming back before then keeps it open. Save before you leave: closing drops anything unsaved.",
                    checked = on,
                    onChange = store::setAutoClose,
                )
            }
            item {
                ToggleCard(
                    title = "Close games left in ES-DE",
                    text = "Back in ES-DE with a game still running, it closes after $seconds seconds even with the screen on. Off: it waits for the screen to go off or another emulator.",
                    checked = onHome,
                    onChange = store::setCloseOnHome,
                )
            }
            item {
                SectionCard {
                    Text("Wait before closing", fontWeight = FontWeight.SemiBold)
                    var value by remember(seconds) { mutableFloatStateOf(seconds.toFloat()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = value,
                            onValueChange = { value = it },
                            onValueChangeFinished = { store.setAutoCloseDelay(value.toInt()) },
                            valueRange = 5f..60f,
                            steps = 10,
                            modifier = Modifier.weight(1f).focusOutline(PillShape),
                        )
                        Text("${value.toInt()} s", modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            if (waiting.isNotEmpty()) {
                item { Heading("Closing soon") }
                items(waiting.entries.toList(), key = { it.key }) { (pkg, at) ->
                    val app = apps.firstOrNull { it.pkg == pkg }
                    val left = ((at - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
                    SectionCard { Text("${app?.label ?: pkg} closes in $left s") }
                }
            }
            item { Heading("Emulators") }
            val emulators = profiles.filter { !it.isDefault && it.apps.isNotEmpty() }
            if (emulators.isEmpty()) item { Muted("Link apps to a profile and they show up here.") }
            items(emulators, key = { it.id }) { profile ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val icon = profile.apps.firstNotNullOfOrNull { pkg -> apps.firstOrNull { it.pkg == pkg }?.icon }
                        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(profile.name, fontWeight = FontWeight.SemiBold)
                            Muted(if (profile.autoClose) "Closes when left behind" else "Stays open")
                        }
                        Switch(
                            checked = profile.autoClose,
                            onCheckedChange = { store.setAutoClose(profile.id, it) },
                            enabled = on,
                            modifier = Modifier.focusOutline(PillShape),
                            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Heading("Other apps", Modifier.weight(1f))
                    TextButton(onClick = onAddApps, modifier = Modifier.focusOutline(PillShape)) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Text("Add apps")
                    }
                }
            }
            if (extras.isEmpty()) {
                item { Muted("Add apps without a profile (a browser, say) and they close the same way once you've left them.", Modifier.padding(horizontal = 4.dp)) }
            }
            items(extras.sortedBy { pkg -> apps.firstOrNull { it.pkg == pkg }?.label?.lowercase() ?: pkg }, key = { "extra:$it" }) { pkg ->
                val app = apps.firstOrNull { it.pkg == pkg }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        app?.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(app?.label ?: pkg, fontWeight = FontWeight.SemiBold)
                            Muted(if (app == null) "Not installed" else "Closes when left behind")
                        }
                        TextButton(onClick = { store.setAutoCloseApps(extras - pkg) }, modifier = Modifier.focusOutline(PillShape)) {
                            Text("Remove")
                        }
                    }
                }
            }
            item {
                Muted(
                    "Never closed: ES-DE and other home screens, Shizuku, Pocket Automator, and any app you haven't added here.",
                    Modifier.padding(4.dp),
                )
            }
        }
    }
}
