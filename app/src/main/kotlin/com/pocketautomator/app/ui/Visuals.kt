package com.pocketautomator.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Toys
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketautomator.app.Device
import com.pocketautomator.app.Knob

/** How each mode looks: an icon, a color, and a word or two to go with it. */
data class Look(val icon: ImageVector, val color: Color, val label: String, val hint: String = "")

val StandardGreen = Color(0xFF52D48A)
val PerformanceAmber = Color(0xFFFFB547)
val HighRed = Color(0xFFFF5A5F)
val FanBlue = Color(0xFF5CC8FF)

fun performanceLook(value: Int): Look = when (value) {
    Knob.PERF_STANDARD -> Look(Icons.Rounded.Eco, StandardGreen, "Standard", "Cool and long-lasting")
    Knob.PERF_HIGH -> Look(Icons.Rounded.LocalFireDepartment, HighRed, "High Performance", "Every core at full speed")
    else -> Look(Icons.Rounded.Speed, PerformanceAmber, "Performance", "Balanced speed")
}

fun fanLook(value: Int): Look = when (value) {
    Knob.FAN_QUIET -> Look(Icons.Rounded.Bedtime, Color(0xFF9FA8FF), "Quiet", "Low and steady")
    Knob.FAN_SMART -> Look(Icons.Rounded.AutoMode, FanBlue, "Smart", "Follows the temperature")
    Knob.FAN_SPORT -> Look(Icons.Rounded.DirectionsRun, Color(0xFFFF8A4C), "Sport", "Fast, for heavy games")
    else -> Look(Icons.Rounded.Block, Color(0xFF9A9AA5), "Off", "No fan at all")
}

fun knobIcon(knob: Knob): ImageVector = when (knob) {
    Knob.PERFORMANCE -> Icons.Rounded.Speed
    Knob.FAN -> Icons.Rounded.Toys
    Knob.TRIGGERS -> Icons.Rounded.VideogameAsset
    Knob.BRIGHTNESS -> Icons.Rounded.BrightnessMedium
    Knob.REFRESH -> Icons.Rounded.Monitor
    Knob.DND -> Icons.Rounded.DoNotDisturbOn
    Knob.WIFI -> Icons.Rounded.Wifi
    Knob.BLUETOOTH -> Icons.Rounded.Bluetooth
    Knob.GAME_SCREEN -> Icons.Rounded.Tune
}

/** A small colored pill: an icon and a word. */
@Composable
fun Badge(look: Look, modifier: Modifier = Modifier, text: String = look.label) {
    Row(
        modifier
            .background(look.color.copy(alpha = 0.18f), PillShape)
            .border(1.dp, look.color.copy(alpha = 0.45f), PillShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(look.icon, contentDescription = null, tint = look.color, modifier = Modifier.size(15.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/**
 * The badges for a profile's performance and fan modes (what it leaves alone
 * isn't shown). [compact] shortens them for the cards: "High", "Smart".
 */
@Composable
fun ModeBadges(settings: Map<Knob, Int>, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        settings[Knob.PERFORMANCE]?.let { value ->
            val look = performanceLook(value)
            Badge(look, text = if (compact && value == Knob.PERF_HIGH) "High" else look.label)
        }
        settings[Knob.FAN]?.let { Badge(fanLook(it), text = if (compact) fanLook(it).label else "${fanLook(it).label} fan") }
        val others = settings.keys.count { it != Knob.PERFORMANCE && it != Knob.FAN }
        if (others > 0) Badge(Look(Icons.Rounded.Tune, Color(0xFFB9B9C6), "+$others more"))
    }
}

/** A fan icon that spins, faster for the stronger modes; still when the fan is off. */
@Composable
fun SpinningFan(mode: Int?, tint: Color, modifier: Modifier = Modifier) {
    val period = when (mode) {
        Knob.FAN_SPORT -> 500
        Knob.FAN_SMART -> 1100
        Knob.FAN_QUIET -> 2200
        else -> 0
    }
    if (period == 0) {
        Icon(Icons.Rounded.Toys, contentDescription = null, tint = tint.copy(alpha = 0.5f), modifier = modifier)
        return
    }
    val spin = rememberInfiniteTransition(label = "fan")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart),
        label = "fanAngle",
    )
    Icon(Icons.Rounded.Toys, contentDescription = null, tint = tint, modifier = modifier.rotate(angle))
}

/** "Performance" etc. for any knob value, for places that just need words. */
fun describe(knob: Knob, value: Int): String = Device.describe(knob, value)
