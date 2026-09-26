package com.pocketautomator.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.pocketautomator.app.Device

/** An app a profile can be linked to. */
class InstalledApp(val pkg: String, val label: String, val icon: ImageBitmap?, val emulator: Boolean, val color: Int)

object InstalledApps {

    private val known = (Device.HEAVY + Device.RETRO + Device.OTHER_EMULATORS).toSet()

    @Volatile private var cache: List<InstalledApp>? = null

    /** The accent, for icons with no color of their own. */
    private const val FALLBACK = 0xFFFF5A6E.toInt()

    /** Every app with a launcher icon, emulators first. Blocking: loads icons. */
    fun load(context: Context, fresh: Boolean = false): List<InstalledApp> {
        if (!fresh) cache?.let { return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { info ->
                val bitmap = runCatching { pm.getApplicationIcon(info).toBitmap(96, 96) }.getOrNull()
                val label = pm.getApplicationLabel(info).toString()
                InstalledApp(
                    info.packageName,
                    label,
                    bitmap?.asImageBitmap(),
                    info.packageName in known || looksLikeEmulator(info.packageName, label),
                    bitmap?.let { runCatching { Art.vividColor(it, FALLBACK) }.getOrNull() } ?: FALLBACK,
                )
            }
            .sortedWith(compareBy<InstalledApp>({ !it.emulator }, { it.label.lowercase() }))
        cache = apps
        return apps
    }

    fun label(context: Context, pkg: String): String =
        cache?.firstOrNull { it.pkg == pkg }?.label
            ?: runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }
                .getOrDefault(pkg)

    private fun looksLikeEmulator(pkg: String, label: String): Boolean {
        val text = "$pkg $label".lowercase()
        return listOf("emu", "retroarch", "sx2", "ppsspp", "dolphin", "citra", "yuzu", "winlator", "drastic", "mupen")
            .any { it in text }
    }
}
