package com.pocketautomator.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * The short message a switch shows. Android 13 drops toasts from an app in
 * the background whose notifications are off, which is what this app is
 * almost all the time, so it draws its own small pill over the game instead
 * ("display over other apps", granted through Shizuku by [Automator]). Without
 * that permission it falls back to a toast. Main thread only.
 */
class Overlay(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private val hide = Runnable { remove() }

    fun show(message: String) {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            return
        }
        handler.removeCallbacks(hide)
        val text = view ?: TextView(context).also { made ->
            made.setTextColor(0xFFFFFFFF.toInt())
            made.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            made.typeface = Typeface.DEFAULT_BOLD
            val dp = context.resources.displayMetrics.density
            made.setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (10 * dp).toInt())
            made.background = GradientDrawable().apply {
                cornerRadius = 24 * dp
                setColor(0xFF202024.toInt())
                setStroke((1 * dp).toInt(), 0x55FFFFFF)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (28 * dp).toInt()
                windowAnimations = android.R.style.Animation_Toast
                title = "Pocket Automator"
            }
            runCatching { windows.addView(made, params) }.onFailure {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                return
            }
            view = made
        }
        text.text = message
        handler.postDelayed(hide, SHOW_MS)
    }

    fun remove() {
        handler.removeCallbacks(hide)
        view?.let { runCatching { windows.removeView(it) } }
        view = null
    }

    private companion object {
        const val SHOW_MS = 2500L
    }
}
