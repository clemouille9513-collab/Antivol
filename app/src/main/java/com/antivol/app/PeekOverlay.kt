package com.antivol.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Écran affiché par-dessus tout quand plusieurs visages regardent le téléphone.
 * Un seul bouton « Déverrouiller », sans code : il sert juste à cacher l'écran.
 */
class PeekOverlay(
    private val ctx: Context,
    private val onDismiss: () -> Unit
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null

    val isShown: Boolean get() = root != null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show() {
        if (root != null) return

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFB3001B.toInt(), 0xFF000000.toInt(), 0xFF0A3CFF.toInt())
            )
            setPadding(dp(28), dp(48), dp(28), dp(24))
            isClickable = true
        }
        val title = TextView(ctx).apply {
            text = "Quelqu'un regarde ton écran"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val button = Button(ctx).apply {
            text = "Déverrouiller"
            isAllCaps = false
            textSize = 18f
            setOnClickListener {
                hide()
                onDismiss()
            }
        }
        layout.addView(title)
        layout.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(32) }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        )
        wm.addView(layout, params)
        root = layout
    }

    fun hide() {
        root?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        root = null
    }
}
