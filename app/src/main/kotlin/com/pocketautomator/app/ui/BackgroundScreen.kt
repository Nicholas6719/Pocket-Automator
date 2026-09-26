package com.pocketautomator.app.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
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
fun BackgroundScreen(apps: List<InstalledApp>, onBack: () -> Unit) {
    val store = Store.get(LocalContext.current)
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val on by store.autoClose.collectAsStateWithLifecycle()
    val seconds by store.autoCloseDelay.collectAsStateWithLifecycle()
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
                    text = "Once you leave an emulator (back to ES-DE, say), it closes $seconds seconds after the screen goes off or another emulator opens. Sleeping mid-game never closes the game you're in, and coming back before then keeps it open. Save before you leave: closing drops anything unsaved.",
                    checked = on,
                    onChange = store::setAutoClose,
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
            item { Heading("Choose which apps auto-close") }
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
                Muted(
                    "Only apps with a profile are ever closed: ES-DE, Syncthing, Shizuku and Pocket Automator never are.",
                    Modifier.padding(4.dp),
                )
            }
        }
    }
}
