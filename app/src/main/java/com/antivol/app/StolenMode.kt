package com.antivol.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Mode "volé" : phrases à voix haute en boucle et/ou sirène,
 * avec le volume remis au maximum chaque seconde.
 */
class StolenMode(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending: String? = null
    private var running = false
    private var speech = false
    private var tick = 0

    private val phrases = listOf(
        "Alerte. Ce téléphone a été volé.",
        "J'ai été volé. Appelez la police.",
        "Alerte. On m'a volé. Le propriétaire est prévenu.",
        "Ce téléphone est localisé. Rendez-le."
    )

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            maxVolume()
            if (speech && tick % 8 == 0) say(phrases[(tick / 8) % phrases.size])
            tick++
            handler.postDelayed(this, 1000)
        }
    }

    fun start(siren: Boolean, withSpeech: Boolean) {
        if (withSpeech) speech = true
        if (!running) {
            running = true
            tick = 0
            handler.post(loop)
        }
        maxVolume()
        if (siren && player == null) startSiren()
    }

    fun stop() {
        running = false
        speech = false
        handler.removeCallbacks(loop)
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false
        pending = null
    }

    /** Dit une phrase à voix haute une seule fois. */
    fun say(text: String) {
        maxVolume()
        if (tts == null) {
            pending = text
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.FRENCH
                    ttsReady = true
                    pending?.let { speakNow(it) }
                    pending = null
                }
            }
        } else if (ttsReady) {
            speakNow(text)
        }
    }

    private fun speakNow(text: String) {
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "antivol")
    }

    private fun maxVolume() {
        for (stream in intArrayOf(AudioManager.STREAM_ALARM, AudioManager.STREAM_MUSIC)) {
            try {
                am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0)
            } catch (_: Exception) {
            }
        }
    }

    private fun startSiren() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            player = null
        }
    }
}
