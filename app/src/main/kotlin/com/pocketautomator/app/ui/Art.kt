package com.pocketautomator.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Game art from ES-DE's folder, shrunk to what the screen needs and kept in memory. */
object Art {

    private val cache = LruCache<String, ImageBitmap>(80)

    /** [file] decoded no larger than about [maxSide] pixels on its long side. Blocking. */
    fun load(file: File, maxSide: Int): ImageBitmap? {
        val key = "${file.path}@$maxSide"
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bitmap.asImageBitmap().also { cache.put(key, it) }
    }

    /**
     * The icon's most vivid color, for tinting its card: the hue that carries
     * the most saturated, bright pixels, at an even strength so every card
     * reads the same.
     */
    fun vividColor(bitmap: Bitmap, fallback: Int): Int {
        val bins = FloatArray(36)
        val hsv = FloatArray(3)
        val step = maxOf(1, bitmap.width / 24)
        for (x in 0 until bitmap.width step step) {
            for (y in 0 until bitmap.height step step) {
                val c = bitmap.getPixel(x, y)
                if (android.graphics.Color.alpha(c) < 128) continue
                android.graphics.Color.colorToHSV(c, hsv)
                val weight = hsv[1] * hsv[2]
                if (hsv[1] < 0.25f || hsv[2] < 0.25f) continue
                bins[(hsv[0] / 10f).toInt().coerceIn(0, 35)] += weight
            }
        }
        val best = bins.indices.maxByOrNull { bins[it] } ?: return fallback
        if (bins[best] < 1f) return fallback
        return android.graphics.Color.HSVToColor(floatArrayOf(best * 10f + 5f, 0.62f, 0.78f))
    }
}

/** A cover or banner from [file], filling [modifier]; a soft gradient while it loads or if there is none. */
@Composable
fun GameArt(file: File?, modifier: Modifier, maxSide: Int = 360, placeholder: Color = Color(0xFF2A2A31)) {
    val image by produceState<ImageBitmap?>(null, file, maxSide) {
        value = file?.let { withContext(Dispatchers.IO) { runCatching { Art.load(it, maxSide) }.getOrNull() } }
    }
    val shown = image
    if (shown != null) {
        Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(Brush.linearGradient(listOf(placeholder, placeholder.copy(alpha = 0.55f)))))
    }
}
