package com.antivol.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Écran de blocage plein écran affiché PAR-DESSUS toutes les applis.
 * Il demande les codes dans l'ordre (code 1, puis code 2, etc.). Les codes peuvent contenir
 * des lettres, des chiffres et des symboles.
 * Un code de secours (usage unique) peut aussi tout débloquer.
 * [onWrong] est appelé à chaque mauvais code avec le nombre total de mauvais codes.
 */
class LockOverlay(
    private val ctx: Context,
    private val onWrong: (Int) -> Unit,
    private val onUnlocked: () -> Unit,
    private val title: String = "Appareil verrouillé",
    private val type: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    private val onCancel: (() -> Unit)? = null,
    /** Envoie un code de secours par e-mail et renvoie le message à afficher. Null = pas d'e-mail enregistré. */
    private val onMail: (() -> String)? = null
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private val total = CodeStore.count(ctx)
    private var step = 0
    private var wrong = 0
    private var recoveryMode = false

    private lateinit var titleView: TextView
    private lateinit var infoView: TextView
    private lateinit var input: EditText
    private lateinit var modeButton: Button
    private var mailButton: Button? = null

    val isShown: Boolean get() = root?.isAttachedToWindow == true

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
            setPadding(dp(28), dp(48), dp(28), dp(24))
            isClickable = true
        }

        titleView = TextView(ctx).apply {
            text = title
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        infoView = TextView(ctx).apply {
            textSize = 16f
            setTextColor(0xFFD0D8E8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(16))
        }
        input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(0x88FFFFFF.toInt())
            hint = "Code"
            textSize = 22f
            gravity = Gravity.CENTER
            setSingleLine(true)
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        val ok = Button(ctx).apply {
            text = "Valider"
            isAllCaps = false
            textSize = 18f
            setOnClickListener { submit() }
        }
        modeButton = Button(ctx).apply {
            isAllCaps = false
            setOnClickListener {
                recoveryMode = !recoveryMode
                input.setText("")
                refresh()
            }
        }

        layout.addView(titleView)
        layout.addView(infoView)
        layout.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout.addView(ok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(16) })
        layout.addView(modeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        onMail?.let { mail ->
            val b = Button(ctx).apply {
                text = "Recevoir un code par e-mail"
                isAllCaps = false
                setOnClickListener {
                    isEnabled = false
                    Thread {
                        val msg = try { mail() } catch (e: Exception) { "Envoi impossible" }
                        input.post { refresh(msg); isEnabled = true }
                    }.start()
                }
            }
            mailButton = b
            layout.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        onCancel?.let { cancel ->
            val b = Button(ctx).apply {
                text = "Annuler"
                isAllCaps = false
                setOnClickListener { hide(); cancel() }
            }
            layout.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        )
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        wm.addView(layout, params)
        lp = params
        root = layout
        refresh()
    }

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
        mailButton?.visibility = if (recoveryMode) View.VISIBLE else View.GONE
        if (recoveryMode) {
            infoView.text = message ?: "Entre un code de secours"
            modeButton.text = "Retour aux codes normaux"
        } else {
            infoView.text = message ?: "Code ${step + 1} sur $total"
            modeButton.text = "Code de secours"
        }
    }

    private fun submit() {
        val code = input.text.toString()
        input.setText("")
        if (code.isEmpty()) { refresh(); return }

        if (recoveryMode) {
            if (CodeStore.checkRecovery(ctx, code) || CodeStore.checkMailCode(ctx, code)) finish() else fail("Code de secours incorrect")
            return
        }
        if (CodeStore.check(ctx, step, code)) {
            step++
            if (step >= total) finish() else refresh()
        } else {
            step = 0
            fail("Code incorrect. On recommence au code 1.")
        }
    }

    private fun fail(message: String) {
        wrong++
        refresh(message)
        onWrong(wrong)
    }

    private fun finish() {
        hide()
        onUnlocked()
    }
}
