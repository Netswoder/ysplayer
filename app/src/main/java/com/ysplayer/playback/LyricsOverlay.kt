package com.ysplayer.playback

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.PowerManager
import android.provider.Settings
import android.view.*
import android.widget.TextView

/** Owned by the playback service; screen-off and lock screen never render lyrics. */
class LyricsOverlay(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private var x = 0f; private var y = 0f; private var initialX = 0; private var initialY = 0
    private fun parameters() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            if (prefs.getBoolean("overlayLocked", false)) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt("overlayX", 0); y = prefs.getInt("overlayY", 180)
        alpha = prefs.getFloat("overlayAlpha", 0.9f).coerceIn(0.2f, 1f)
    }
    fun refreshSettings() { close() }
    fun update(text: String, hasItem: Boolean) {
        val visible = hasItem && prefs.getBoolean("overlay", false) && Settings.canDrawOverlays(context) &&
            context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked && text.isNotEmpty()
        if (!visible) { close(); return }
        if (view == null) {
            val label = object : TextView(context) {
                override fun performClick(): Boolean { super.performClick(); return true }
            }.apply {
                setPadding(24, 16, 24, 16)
                textSize = prefs.getFloat("overlaySize", 20f)
                setTextColor(prefs.getInt("overlayColor", Color.WHITE))
                setBackgroundColor(0xA6222925.toInt())
                gravity = Gravity.CENTER
                maxLines = 5
            }
            label.setOnTouchListener { _, event ->
                val params = label.layoutParams as WindowManager.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { x = event.rawX; y = event.rawY; initialX = params.x; initialY = params.y }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - x).toInt()
                        params.y = (initialY + event.rawY - y).toInt().coerceAtLeast(0)
                        runCatching { windows.updateViewLayout(label, params) }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (kotlin.math.abs(event.rawX - x) < 8 && kotlin.math.abs(event.rawY - y) < 8) label.performClick()
                        prefs.edit().putInt("overlayX", params.x).putInt("overlayY", params.y).apply()
                    }
                }
                true
            }
            if (runCatching { windows.addView(label, parameters()) }.isSuccess) view = label
        }
        if (view?.text?.toString() != text) view?.text = text
    }
    fun close() { view?.let { runCatching { windows.removeView(it) } }; view = null }
}
