package com.pocketautomator.app.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketautomator.app.Shell
import com.pocketautomator.app.Store

/** Choosing which apps use a profile. Each tick takes effect straight away. */
@Composable
fun AppsScreen(id: Int, apps: List<InstalledApp>, onBack: () -> Unit) {
    val store = Store.get(LocalContext.current)
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val profile = profiles.firstOrNull { it.id == id } ?: run { onBack(); return }
    AppPicker(
        title = "Apps for ${profile.name}",
        subtitle = "While one of these is in front, ${profile.name} is used",
        apps = apps,
        checked = { it.pkg in profile.apps },
        note = { app -> profiles.firstOrNull { it.id != id && app.pkg in it.apps }?.let { "Now in ${it.name}; ticking moves it here" } },
        onToggle = { app, on -> store.setApps(id, if (on) profile.apps + app.pkg else profile.apps - app.pkg) },
        onBack = onBack,
    )
}

/**
 * Choosing apps without a profile that auto-close too. Home screens (ES-DE),
 * Shizuku and Pocket Automator itself aren't offered: closing them would stop
 * everything else. Emulators with a profile have their own switches.
 */
@Composable
fun AutoCloseAppsScreen(apps: List<InstalledApp>, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = Store.get(context)
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val extras by store.autoCloseApps.collectAsStateWithLifecycle()
    val homes = remember {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    }
    val linked = profiles.filter { !it.isDefault }.flatMap { it.apps }.toSet()
    val offered = apps.filter { it.pkg !in homes && it.pkg != Shell.SHIZUKU_PACKAGE && it.pkg !in linked }
    AppPicker(
        title = "Add apps to auto-close",
        subtitle = "Closed like your emulators once you've left them",
        apps = offered,
        checked = { it.pkg in extras },
        note = { null },
        onToggle = { app, on -> store.setAutoCloseApps(if (on) extras + app.pkg else extras - app.pkg) },
        onBack = onBack,
    )
}

@Composable
fun AppPicker(
    title: String,
    subtitle: String,
    apps: List<InstalledApp>,
    checked: (InstalledApp) -> Boolean,
    note: (InstalledApp) -> String?,
    onToggle: (InstalledApp, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true) }

    Page(title = title, subtitle = subtitle, onBack = onBack) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search apps") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        if (apps.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Muted("Loading apps…") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.pkg }) { app ->
                val on = checked(app)
                val color = Color(app.color)
                val shape = RoundedCornerShape(16.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape)
                        .background(if (on) color.copy(alpha = 0.22f) else Color(0xFF1E1E24))
                        .border(1.dp, if (on) color.copy(alpha = 0.7f) else Color(0xFF2A2A31), shape)
                        .focusOutline(shape)
                        .clickable { onToggle(app, !on) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp)) {
                        app.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) }
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(app.label, fontWeight = FontWeight.SemiBold)
                        Muted(note(app) ?: if (app.emulator) "Emulator" else app.pkg)
                    }
                    Icon(
                        if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (on) "Selected" else "Not selected",
                        tint = if (on) color else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}
