package com.pocketautomator.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DoNotTouch
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.pocketautomator.app.AutomatorService
import com.pocketautomator.app.Device
import com.pocketautomator.app.Knob
import com.pocketautomator.app.Profile
import com.pocketautomator.app.Store

@Composable
fun EditScreen(
    id: Int,
    apps: List<InstalledApp>,
    library: Library,
    knobs: List<Knob>,
    refreshRates: List<Int>,
    onChooseApps: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = Store.get(context)
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val profile = profiles.firstOrNull { it.id == id } ?: run { onBack(); return }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val linked = profile.apps.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg } }
    val color = when {
        profile.isDefault -> AccentAlt
        linked.isNotEmpty() -> Color(linked.first().color)
        else -> Accent
    }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 880.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Header(profile, linked, library, color, onBack, onRename = { renaming = true })
            }
            if (profile.isDefault) {
                item {
                    Muted(
                        "Default is used on your home screen (ES-DE) and in every app without a profile. Anything a game changes that Default leaves on \"Don't change\" goes back to how it was when you leave the game.",
                        Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            item { Heading("Performance mode") }
            item {
                TileRow(
                    current = profile.settings[Knob.PERFORMANCE],
                    options = Device.PERFORMANCE.map { performanceLook(it.value) to it.value },
                    onPick = { store.setKnob(profile.id, Knob.PERFORMANCE, it) },
                )
            }
            item { Heading("Fan") }
            item {
                TileRow(
                    current = profile.settings[Knob.FAN],
                    options = Device.FAN.map { fanLook(it.value) to it.value },
                    onPick = { store.setKnob(profile.id, Knob.FAN, it) },
                )
            }

            if (!profile.isDefault) {
                item { Heading("Apps that use this profile") }
                item { LinkedApps(profile, apps, onChooseApps) }
            }

            item { Heading("More settings") }
            items(
                knobs.filter { it != Knob.PERFORMANCE && it != Knob.FAN && !(profile.isDefault && it == Knob.GAME_SCREEN) },
                key = { it.key },
            ) { knob ->
                KnobCard(knob, profile.settings[knob], refreshRates, onChange = { store.setKnob(profile.id, knob, it) })
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { AutomatorService.current?.applyNow(profile) }, modifier = Modifier.focusOutline(PillShape)) {
                        Text("Apply now")
                    }
                    Spacer(Modifier.weight(1f))
                    if (!profile.isDefault) {
                        TextButton(onClick = { deleting = true }, modifier = Modifier.focusOutline(PillShape)) {
                            Text("Delete profile", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(profile.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename profile") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(Profile.MAX_NAME) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { store.rename(profile.id, name); renaming = false }, modifier = Modifier.focusOutline(PillShape)) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }, modifier = Modifier.focusOutline(PillShape)) { Text("Cancel") }
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${profile.name}?") },
            text = { Text("Its apps go back to using Default.") },
            confirmButton = {
                TextButton(onClick = { deleting = false; store.delete(profile.id); onBack() }, modifier = Modifier.focusOutline(PillShape)) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }, modifier = Modifier.focusOutline(PillShape)) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Header(
    profile: Profile,
    linked: List<InstalledApp>,
    library: Library,
    color: Color,
    onBack: () -> Unit,
    onRename: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val covers = linked.firstOrNull()?.let { library.forEmulator(it.pkg, 4) }.orEmpty()
    Box(
        Modifier.fillMaxWidth().height(150.dp).clip(shape)
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.75f), Color(0xFF1C1C22)))),
    ) {
        Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.focusOutline(PillShape)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Box(Modifier.padding(start = 6.dp).size(64.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x33000000)), contentAlignment = Alignment.Center) {
                val icon = linked.firstOrNull()?.icon
                when {
                    profile.isDefault -> Icon(Icons.Rounded.Home, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                    icon != null -> Image(icon, contentDescription = null, modifier = Modifier.size(64.dp))
                    else -> Icon(Icons.Rounded.VideogameAsset, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(profile.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ModeBadges(profile.settings)
                OutlinedButton(onClick = onRename, modifier = Modifier.focusOutline(PillShape)) { Text("Rename", color = Color.White) }
            }
            // This emulator's games from ES-DE, beside the name rather than under it.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                covers.forEach { entry ->
                    GameArt(entry.cover, Modifier.width(76.dp).height(108.dp).clip(RoundedCornerShape(10.dp)), maxSide = 260)
                }
            }
        }
    }
}

/** Big tiles, one per mode, plus "Don't change". */
@Composable
private fun TileRow(current: Int?, options: List<Pair<Look, Int>>, onPick: (Int?) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (look, value) ->
            Tile(look, selected = current == value, modifier = Modifier.weight(1f)) { onPick(value) }
        }
        Tile(
            Look(Icons.Rounded.DoNotTouch, Color(0xFF8E8E99), "Don't change", "Leave it as it is"),
            selected = current == null,
            modifier = Modifier.weight(1f),
        ) { onPick(null) }
    }
}

@Composable
private fun Tile(look: Look, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .height(104.dp)
            .clip(shape)
            .background(if (selected) look.color.copy(alpha = 0.28f) else Color(0xFF1E1E24))
            .border(if (selected) 2.dp else 1.dp, if (selected) look.color else Color(0xFF2E2E36), shape)
            .focusOutline(shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(look.icon, contentDescription = null, tint = look.color, modifier = Modifier.size(28.dp))
        Text(look.label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(look.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
fun ToggleCard(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Muted(text)
            }
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                modifier = Modifier.focusOutline(PillShape),
                colors = SwitchDefaults.colors(checkedTrackColor = Accent),
            )
        }
    }
}

@Composable
private fun LinkedApps(profile: Profile, apps: List<InstalledApp>, onChooseApps: () -> Unit) {
    SectionCard {
        if (profile.apps.isEmpty()) Muted("No apps yet. While one of them is in front, this profile is used.")
        profile.apps.sortedBy { pkg -> apps.firstOrNull { it.pkg == pkg }?.label?.lowercase() ?: pkg }.forEach { pkg ->
            val app = apps.firstOrNull { it.pkg == pkg }
            Row(verticalAlignment = Alignment.CenterVertically) {
                app?.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))) }
                Text(app?.label ?: "$pkg (not installed)", modifier = Modifier.padding(start = 10.dp))
            }
        }
        OutlinedButton(onClick = onChooseApps, modifier = Modifier.focusOutline(PillShape)) { Text("Choose apps") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnobCard(knob: Knob, value: Int?, refreshRates: List<Int>, onChange: (Int?) -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(knobIcon(knob), contentDescription = null, tint = AccentAlt, modifier = Modifier.size(22.dp))
            Text(Device.title(knob), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
        }
        hint(knob)?.let { Muted(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Don't change", value == null) { onChange(null) }
            if (knob == Knob.BRIGHTNESS) {
                Choice("Auto", value == Knob.BRIGHTNESS_AUTO) { onChange(Knob.BRIGHTNESS_AUTO) }
                Choice("Set level", value != null && value != Knob.BRIGHTNESS_AUTO) {
                    if (value == null || value == Knob.BRIGHTNESS_AUTO) onChange(Device.brightnessRaw(50))
                }
            } else {
                if (knob == Knob.REFRESH) Choice("System default", value == Knob.REFRESH_DEFAULT) { onChange(Knob.REFRESH_DEFAULT) }
                Device.options(knob, refreshRates).forEach { option ->
                    Choice(option.label, value == option.value) { onChange(option.value) }
                }
            }
        }
        if (knob == Knob.BRIGHTNESS && value != null && value != Knob.BRIGHTNESS_AUTO) {
            var level by remember(value) { mutableFloatStateOf(Device.brightnessPercent(value).toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = level,
                    onValueChange = { level = it },
                    onValueChangeFinished = { onChange(Device.brightnessRaw(level.toInt())) },
                    valueRange = 1f..100f,
                    modifier = Modifier.weight(1f).focusOutline(PillShape),
                )
                Text("${level.toInt()}%", modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.focusOutline(MaterialTheme.shapes.small))
}

private fun hint(knob: Knob): String? = when (knob) {
    Knob.TRIGGERS -> "Analog for racing games; Digital for games that want a click."
    Knob.GAME_SCREEN -> "Moves the game to this screen as it opens."
    else -> null
}
