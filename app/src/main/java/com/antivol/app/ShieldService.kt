package com.antivol.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager

/**
 * Protection renforcée (service d'accessibilité).
 *
 * Quand le téléphone est verrouillé par l'antivol :
 *   la barre de notifications, les réglages rapides, le menu d'extinction, les réglages
 *   et toute autre appli sont renvoyés à l'accueil, sous l'écran de codes.
 * Quand la protection est active :
 *   les pages des Réglages / de la désinstallation qui parlent d'Antivol (infos de l'appli,
 *   administrateur, accessibilité, désinstaller, forcer l'arrêt) demandent d'abord les codes.
 *
 * Sert aussi à allumer ou couper le mode avion à distance (touche la tuile des réglages rapides).
 */
class ShieldService : AccessibilityService() {

    companion object {
        @Volatile var instance: ShieldService? = null

        fun enabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(ctx, ShieldService::class.java)
            return list.split(":").any {
                val c = ComponentName.unflattenFromString(it)
                c != null && c.packageName == me.packageName && c.className == me.className
            }
        }

        fun authorize(ctx: Context, ms: Long) = Prefs.setLong(ctx, "authUntil", System.currentTimeMillis() + ms)
        fun authorized(ctx: Context) = System.currentTimeMillis() < Prefs.long(ctx, "authUntil")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastAct = 0L
    private var suspendUntil = 0L
    private var authOverlay: LockOverlay? = null
    private var cachedAt = 0L
    private var cachedAllowed = emptySet<String>()

    private val guarded = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.samsung.android.lool"
    )

    private val phone = setOf(
        "com.samsung.android.incallui", "com.android.incallui", "com.google.android.dialer",
        "com.samsung.android.dialer", "com.android.server.telecom"
    )

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        try { authOverlay?.hide() } catch (_: Exception) {}
        authOverlay = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    /** Applis qui ont le droit d'être à l'écran pendant le blocage : accueil, clavier, téléphone. */
    private fun allowedPackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt < 60_000 && cachedAllowed.isNotEmpty()) return cachedAllowed
        val s = mutableSetOf<String>()
        s += phone
        s += "android"
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, 0).forEach { s += it.activityInfo.packageName }
        } catch (_: Exception) {}
        try {
            getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { s += it.packageName }
        } catch (_: Exception) {}
        cachedAllowed = s
        cachedAt = now
        return s
    }

    private fun looksLikePanel(cls: String): Boolean {
        val c = cls.lowercase()
        return listOf("shade", "notification", "quicksetting", "qspanel", "qs.", "globalactions", "powermenu", "poweroff", "recents")
            .any { c.contains(it) }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        val locked = Prefs.isLocked(this) || Prefs.isStolen(this)
        val protect = Prefs.isEnabled(this)
        if (!locked && !protect) return
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED && type != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val now = SystemClock.elapsedRealtime()
        // Quand le S22 est verrouillé on réagit presque sans délai ; sinon on garde une petite pause.
        if (now < suspendUntil || now - lastAct < (if (locked) 60 else 600)) return
        // L'écran de verrouillage Samsung (code, empreinte) n'est pas touché.
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return
        val cls = e.className?.toString() ?: ""

        if (locked) {
            val bad = when {
                // Un clic dans le panneau (roue des réglages, bouton d'extinction...) : on referme tout de suite.
                pkg == "com.android.systemui" ->
                    type == AccessibilityEvent.TYPE_VIEW_CLICKED || looksLikePanel(cls) ||
                        (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED && shadeOpen())
                pkg in allowedPackages() -> false
                else -> true
            }
            if (bad) {
                lastAct = now
                kick(pkg == "com.android.systemui")
                return
            }
        }

        if (protect && pkg in guarded && !authorized(this) && authOverlay == null) {
            if (mentionsApp()) {
                lastAct = now
                performGlobalAction(GLOBAL_ACTION_HOME)
                Report.event(this, "admin", "Quelqu'un a ouvert les réglages de l'antivol")
                askCodes()
            }
        }
    }

    /** Vrai si le panneau de notifications ou de réglages rapides est ouvert. */
    private fun shadeOpen(): Boolean = try {
        windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM && it.root?.packageName == "com.android.systemui" && it.isActive }
    } catch (_: Exception) { false }

    /**
     * Renvoie à l'accueil tout de suite, puis insiste : les Réglages peuvent masquer l'écran de blocage
     * quelques instants, donc on ne laisse aucune pause entre les essais.
     */
    private fun kick(fromPanel: Boolean) {
        if (fromPanel && Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        performGlobalAction(GLOBAL_ACTION_HOME)
        for (d in longArrayOf(40, 90, 160, 260, 400)) {
            handler.postDelayed({
                if (Prefs.isLocked(this) || Prefs.isStolen(this)) {
                    val front = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
                    if (front != null && front != packageName && front !in allowedPackages()) performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }, d)
        }
    }

    private fun mentionsApp(): Boolean {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
        return try {
            !root.findAccessibilityNodeInfosByText("Antivol").isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun askCodes() {
        if (authOverlay != null) return
        if (CodeStore.count(this) == 0) return
        val o = LockOverlay(
            this,
            onWrong = { n -> Report.event(this, "wrong", "$n mauvais code(s) pour ouvrir les réglages") },
            onUnlocked = {
                authOverlay = null
                authorize(this, 180_000)
            },
            title = "Codes requis",
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            onCancel = { authOverlay?.hide(); authOverlay = null }
        )
        authOverlay = o
        try { o.show() } catch (_: Exception) { authOverlay = null }
    }

    // ---------- Capture de l'écran (écran en direct) ----------

    private val shotExec = Executors.newSingleThreadExecutor()

    /**
     * Prend une capture de l'écran sans demander d'autorisation à chaque fois (Android 11 et plus).
     * Renvoie une image JPEG de [maxW] pixels de large, ou null si Android refuse (fenêtre protégée, trop rapide).
     */
    @SuppressLint("NewApi")
    fun screenshot(maxW: Int, quality: Int, done: (ByteArray?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { done(null); return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExec, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var out: ByteArray? = null
                    try {
                        val hb = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        try { hb.close() } catch (_: Exception) {}
                        if (soft != null) {
                            val w = minOf(maxW, soft.width)
                            val h = (soft.height * (w.toFloat() / soft.width)).toInt()
                            val scaled = if (w == soft.width) soft else Bitmap.createScaledBitmap(soft, w, h, true)
                            val bo = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, quality, bo)
                            out = bo.toByteArray()
                        }
                    } catch (_: Exception) {
                    }
                    done(out)
                }

                override fun onFailure(errorCode: Int) { done(null) }
            })
        } catch (_: Exception) {
            done(null)
        }
    }

    // ---------- Mode avion à distance ----------

    /** Ouvre les réglages rapides et touche la tuile « Mode avion » si son état n'est pas déjà le bon. */
    fun setAirplane(on: Boolean, done: (Boolean) -> Unit) {
        suspendUntil = SystemClock.elapsedRealtime() + 12_000
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        tryTile(on, 0, done)
    }

    private fun tryTile(on: Boolean, attempt: Int, done: (Boolean) -> Unit) {
        handler.postDelayed({
            val root = try { rootInActiveWindow } catch (_: Exception) { null }
            val tile = root?.let { findTile(it) }
            if (tile != null) {
                val d = ((tile.contentDescription ?: "").toString() + " " + (tile.text ?: "")).lowercase()
                val isOn = (d.contains("activé") && !d.contains("désactivé") && !d.contains("desactive")) ||
                    (d.contains(" on") && !d.contains(" off"))
                if (isOn != on) clickNode(tile)
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME); done(true) }, 800)
            } else if (attempt < 4) {
                tryTile(on, attempt + 1, done)
            } else {
                performGlobalAction(GLOBAL_ACTION_HOME)
                done(false)
            }
        }, 800)
    }

    private fun findTile(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (label in listOf("Mode avion", "Airplane mode", "Flight mode", "Mode Avion")) {
            val l = try { root.findAccessibilityNodeInfosByText(label) } catch (_: Exception) { null }
            if (!l.isNullOrEmpty()) return l[0]
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo) {
        var c: AccessibilityNodeInfo? = n
        var guard = 0
        while (c != null && guard < 6) {
            if (c.isClickable) { c.performAction(AccessibilityNodeInfo.ACTION_CLICK); return }
            c = c.parent
            guard++
        }
    }
}
