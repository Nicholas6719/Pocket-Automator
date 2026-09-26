package com.pocketautomator.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketautomator.app.AutomatorService
import com.pocketautomator.app.BuildConfig
import com.pocketautomator.app.Device
import com.pocketautomator.app.RetroidLists
import com.pocketautomator.app.Shell
import com.pocketautomator.app.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DiagnosticsScreen(library: Library, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = Store.get(context)
    val log by store.log.collectAsStateWithLifecycle()
    val now by store.now.collectAsStateWithLifecycle()
    var messages by remember { mutableStateOf(store.messages) }
    var report by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(now) { report = withContext(Dispatchers.IO) { facts(context, now) + libraryFacts(library) } }

    Page(
        title = "Diagnostics",
        subtitle = "Pocket Automator ${BuildConfig.VERSION_NAME}",
        onBack = onBack,
        actions = {
            OutlinedButton(onClick = { copy(context, report, log) }, modifier = Modifier.focusOutline(PillShape)) { Text("Copy report") }
        },
    ) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Show a message on each switch", modifier = Modifier.weight(1f))
                        Switch(
                            checked = messages,
                            onCheckedChange = { messages = it; store.messages = it },
                            modifier = Modifier.focusOutline(PillShape),
                        )
                    }
                }
            }
            item { Heading("This device") }
            items(report) { Mono(it) }
            item { Heading("Recent activity") }
            if (log.isEmpty()) item { Muted("Nothing yet.") }
            items(log) { Mono(it) }
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
}

/** What matters when something doesn't switch. Blocking: reads settings through Shizuku. */
private fun facts(context: Context, now: Store.Now): List<String> {
    val out = mutableListOf(
        "model: ${Build.MANUFACTURER} ${Build.MODEL} → ${Device.current}",
        "firmware: ${Build.DISPLAY}",
        "android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})",
        "shizuku: ${Shell.status(context)}",
        "service running: ${AutomatorService.running}; watching apps: ${now.watching}",
        "draw over apps: ${Settings.canDrawOverlays(context)}",
        "odintools installed: ${runCatching { context.packageManager.getPackageInfo("de.langerhans.odintools", 0) }.isSuccess}",
    )
    now.readings?.let { r ->
        out += "readings: " + r.values.entries.joinToString { "${it.key.key}=${it.value}" } + (r.fanRpm?.let { ", fanRpm=$it" } ?: "")
    }
    if (Shell.ready) {
        for (key in listOf(RetroidLists.AUTO_RUN_DISABLED, "auto_clean_processes", RetroidLists.CLEAN_IGNORED, "smart_fan_mode_switch")) {
            out += "$key: " + runCatching { Shell.run("settings", "get", "system", key).out.trim() }.getOrDefault("?")
        }
    }
    return out
}

private fun libraryFacts(library: Library): List<String> = listOf(
    "es-de: ${library.home?.path ?: "not found"} (files access: ${Library.canRead()}), ${library.size} games, ${library.recent.size} recent",
)

private fun copy(context: Context, report: List<String>, log: List<String>) {
    val text = (report + "" + "log:" + log).joinToString("\n")
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pocket Automator report", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
