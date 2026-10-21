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
 * Écran de blocage plein écran affiché PAR-DESSUS toutes les applis.
 * Il demande les codes dans l'ordre (code 1, puis code 2, etc.).
 * Un seul code de secours (usage unique) peut aussi tout débloquer.
 * Le clavier est dessiné ici : pas besoin du clavier Android.
 */
class LockOverlay(
    private val ctx: Context,
    private val onUnlocked: () -> Unit
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null

    private val total = CodeStore.count(ctx)
    private var step = 0
    private var recoveryMode = false
    private val entry = StringBuilder()

    private lateinit var titleView: TextView
    private lateinit var infoView: TextView
    private lateinit var dotsView: TextView
    private lateinit var modeButton: Button

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show() {
        if (root != null) return

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Fond noir, rouge en haut à gauche, bleu en bas à droite
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFB3001B.toInt(), 0xFF000000.toInt(), 0xFF0A3CFF.toInt())
            )
            setPadding(dp(24), dp(48), dp(24), dp(24))
            isClickable = true
        }

        titleView = TextView(ctx).apply {
            text = "Appareil verrouillé"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        infoView = TextView(ctx).apply {
            textSize = 16f
            setTextColor(0xFFB0BEC5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        dotsView = TextView(ctx).apply {
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minHeight = dp(56)
        }
        layout.addView(titleView)
        layout.addView(infoView)
        layout.addView(dotsView)

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("⌫", "0", "OK")
        )
        for (row in rows) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (label in row) {
                val b = Button(ctx).apply {
                    text = label
                    textSize = 24f
                    isAllCaps = false
                    setOnClickListener { onKey(label) }
                }
                line.addView(b, LinearLayout.LayoutParams(dp(88), dp(72)).apply {
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                })
            }
            layout.addView(line)
        }

        modeButton = Button(ctx).apply {
            isAllCaps = false
            setOnClickListener {
                recoveryMode = !recoveryMode
                entry.clear()
                refresh()
            }
        }
        layout.addView(modeButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

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
        lp = params
        root = layout
        refresh()
    }

    private var lp: WindowManager.LayoutParams? = null

    /** Mode éco : écran au plus bas pour économiser la batterie. */
    fun dim(on: Boolean) {
        val p = lp ?: return
        p.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        root?.let { try { wm.updateViewLayout(it, p) } catch (_: Exception) {} }
    }

    fun hide() {
        root?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        root = null
    }

    private fun refresh(message: String? = null) {
        if (recoveryMode) {
            infoView.text = message ?: "Entre un code de secours"
            modeButton.text = "Retour aux codes normaux"
        } else {
            infoView.text = message ?: "Code ${step + 1} sur $total"
            modeButton.text = "Code de secours"
        }
        dotsView.text = "•".repeat(entry.length)
    }

    private fun onKey(label: String) {
        when (label) {
            "⌫" -> if (entry.isNotEmpty()) entry.deleteCharAt(entry.length - 1)
            "OK" -> { submit(); return }
            else -> if (entry.length < 12) entry.append(label)
        }
        refresh()
    }

    private fun submit() {
        val code = entry.toString()
        entry.clear()
        if (code.isEmpty()) { refresh(); return }

        if (recoveryMode) {
            if (CodeStore.checkRecovery(ctx, code)) finish() else refresh("Code de secours incorrect")
            return
        }
        if (CodeStore.check(ctx, step, code)) {
            step++
            if (step >= total) finish() else refresh()
        } else {
            step = 0
            refresh("Code incorrect. On recommence au code 1.")
        }
    }

    private fun finish() {
        hide()
        onUnlocked()
    }
}
