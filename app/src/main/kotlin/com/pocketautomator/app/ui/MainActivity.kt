package com.pocketautomator.app.ui

import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.pocketautomator.app.AutomatorService
import com.pocketautomator.app.Commands
import com.pocketautomator.app.Device
import com.pocketautomator.app.Knob
import com.pocketautomator.app.ProfilesJson
import com.pocketautomator.app.Shell
import com.pocketautomator.app.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var shizuku by mutableStateOf(Shell.Status.NOT_RUNNING)
    private var apps by mutableStateOf(emptyList<InstalledApp>())
    private var odinTools by mutableStateOf(false)
    private var library by mutableStateOf(Library.EMPTY)

    private val shizukuChanged = Shizuku.OnBinderReceivedListener { updateShizuku() }
    private val shizukuGone = Shizuku.OnBinderDeadListener { updateShizuku() }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> updateShizuku() }

    private val backup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(::writeBackup)
    }
    private val restore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::readBackup)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutomatorService.start(this)
        Shizuku.addBinderReceivedListenerSticky(shizukuChanged)
        Shizuku.addBinderDeadListener(shizukuGone)
        Shizuku.addRequestPermissionResultListener(permission)
        val refreshRates = refreshRates()
        val knobs = Device.knobs(refreshRates, if (Device.current == Device.Model.DUO) 2 else 1)

        setContent {
            AutomatorTheme {
                // 0 home, 1 edit, 2 apps, 3 diagnostics, 4 background
                var screen by rememberSaveable { mutableStateOf(0) }
                var profileId by rememberSaveable { mutableStateOf(0) }
                when (screen) {
                    1 -> EditScreen(profileId, apps, library, knobs, refreshRates, onChooseApps = { screen = 2 }, onBack = { screen = 0 })
                    2 -> AppsScreen(profileId, apps, onBack = { screen = 1 })
                    3 -> DiagnosticsScreen(library, onBack = { screen = 0 })
                    4 -> BackgroundScreen(apps, onBack = { screen = 0 })
                    else -> HomeScreen(
                        shizuku = shizuku,
                        odinTools = odinTools,
                        apps = apps,
                        library = library,
                        onShizuku = ::fixShizuku,
                        onBackground = { screen = 4 },
                        onEdit = { profileId = it; screen = 1 },
                        onNew = {
                            profileId = Store.get(this).create("New profile").id
                            screen = 1
                        },
                        onDiagnostics = { screen = 3 },
                        onBackup = { backup.launch("pocket-automator-profiles.json") },
                        onRestore = { restore.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        onRefresh = { AutomatorService.current?.refreshReadingsAsync() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateShizuku()
        // OdinTools switches modes per app from its accessibility service, which would fight this app.
        odinTools = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().contains("de.langerhans.odintools/")
        lifecycleScope.launch {
            apps = withContext(Dispatchers.IO) { InstalledApps.load(this@MainActivity, fresh = true) }
            loadLibrary()
        }
        AutomatorService.current?.refreshReadingsAsync()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuChanged)
        Shizuku.removeBinderDeadListener(shizukuGone)
        Shizuku.removeRequestPermissionResultListener(permission)
        super.onDestroy()
    }

    private fun updateShizuku() {
        runOnUiThread {
            val was = shizuku
            shizuku = Shell.status(this)
            if (shizuku == Shell.Status.READY) {
                seed()
                if (was != Shell.Status.READY && !Library.canRead()) lifecycleScope.launch { loadLibrary() }
            }
        }
    }

    /**
     * Reads ES-DE's games for the art. Reading ES-DE's folder needs "all files
     * access", which Pocket Automator grants itself through Shizuku; it only
     * ever reads there.
     */
    private suspend fun loadLibrary() {
        library = withContext(Dispatchers.IO) {
            if (!Library.canRead() && Shell.ready) {
                runCatching { Shell.run("appops", "set", packageName, "MANAGE_EXTERNAL_STORAGE", "allow") }
            }
            val installed = apps.map { it.pkg }.toSet().ifEmpty { InstalledApps.load(this@MainActivity).map { it.pkg }.toSet() }
            runCatching { Library.load(this@MainActivity, installed) }.getOrDefault(Library.EMPTY)
        }
    }

    private fun fixShizuku() {
        when (Shell.status(this)) {
            Shell.Status.NOT_INSTALLED -> startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")),
            )
            Shell.Status.NOT_RUNNING -> packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PACKAGE)?.let(::startActivity)
            Shell.Status.NO_PERMISSION -> runCatching { Shizuku.requestPermission(REQUEST) }
            Shell.Status.READY -> Unit
        }
        updateShizuku()
    }

    /**
     * The first time Shizuku is ready: Default takes the performance and fan
     * modes the handheld has now, and two starter profiles link the
     * emulators that are installed. All of it can be changed or deleted.
     */
    private fun seed() {
        val store = Store.get(this)
        if (store.seeded) return
        store.seeded = true
        lifecycleScope.launch {
            val now = withContext(Dispatchers.IO) {
                runCatching { Commands.parse(Shell.sh(Commands.readScript).out) }.getOrNull()
            }?.values.orEmpty()
            val installed = withContext(Dispatchers.IO) { InstalledApps.load(this@MainActivity) }.map { it.pkg }.toSet()
            val default = store.default()
            store.save(
                default.copy(
                    settings = default.settings +
                        (Knob.PERFORMANCE to (now[Knob.PERFORMANCE]?.takeIf { it in 0..2 } ?: Knob.PERF_PERFORMANCE)) +
                        (Knob.FAN to (now[Knob.FAN]?.takeIf { v -> Device.FAN.any { it.value == v } } ?: Knob.FAN_SMART)),
                ),
            )
            store.create(
                "Heavy games",
                mapOf(Knob.PERFORMANCE to Knob.PERF_HIGH, Knob.FAN to Knob.FAN_SMART),
                Device.HEAVY.filter { it in installed }.toSet(),
            )
            store.create(
                "Retro",
                mapOf(Knob.PERFORMANCE to Knob.PERF_STANDARD, Knob.FAN to Knob.FAN_SMART),
                Device.RETRO.filter { it in installed }.toSet(),
            )
            store.log("made the starter profiles")
        }
    }

    private fun refreshRates(): List<Int> =
        getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?.supportedModes?.map { it.refreshRate.roundToInt() }?.distinct()?.sorted().orEmpty()

    private fun writeBackup(uri: Uri) {
        val text = ProfilesJson.toJson(Store.get(this).profiles.value).toString(2)
        val ok = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }.isSuccess
        Toast.makeText(this, if (ok) "Profiles saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
    }

    private fun readBackup(uri: Uri) {
        val text = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()
        val profiles = ProfilesJson.fromJson(text)
        if (profiles == null) {
            Toast.makeText(this, "That isn't a Pocket Automator backup", Toast.LENGTH_SHORT).show()
            return
        }
        Store.get(this).replaceAll(profiles)
        Toast.makeText(this, "Restored ${profiles.size} profiles", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST = 7
    }
}
