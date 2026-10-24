package com.antivol.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reconnaissance du propriétaire.
 * - Visage : ML Kit trouve les visages, MobileFaceNet (TensorFlow Lite) en fait une « empreinte » de 192 nombres,
 *   comparée à celle enregistrée (similarité cosinus).
 * - Voix : WeSpeaker (sherpa-onnx) fait l'empreinte de la voix, comparée de la même façon.
 * Les empreintes restent sur le téléphone ; aucune photo ni son n'est gardé.
 */
object Biometrics {
    enum class Kind { OWNER, OTHER, NONE }
    class Result(val kind: Kind, val score: Float)

    const val FACE_THRESHOLD = 0.42f
    const val VOICE_THRESHOLD = 0.32f

    // ---------- Stockage ----------

    private fun save(ctx: Context, key: String, v: FloatArray) = Prefs.setStr(ctx, key, v.joinToString(","))

    private fun load(ctx: Context, key: String): FloatArray? {
        val raw = Prefs.str(ctx, key)
        if (raw.isEmpty()) return null
        val l = raw.split(",").mapNotNull { it.toFloatOrNull() }
        return if (l.isEmpty()) null else l.toFloatArray()
    }

    fun faceEnrolled(ctx: Context) = Prefs.str(ctx, "face_emb").isNotEmpty()
    fun voiceEnrolled(ctx: Context) = Prefs.str(ctx, "voice_emb").isNotEmpty()
    fun clearFace(ctx: Context) = Prefs.setStr(ctx, "face_emb", "")
    fun clearVoice(ctx: Context) = Prefs.setStr(ctx, "voice_emb", "")

    private fun l2(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = max(sqrt(s).toFloat(), 1e-9f)
        return FloatArray(v.size) { v[it] / n }
    }

    private fun cos(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d
    }

    private fun average(list: List<FloatArray>): FloatArray {
        val out = FloatArray(list[0].size)
        for (e in list) for (i in out.indices) out[i] += e[i]
        return l2(out)
    }

    // ---------- Visage ----------

    @Volatile private var detector: FaceDetector? = null
    @Volatile private var tflite: Interpreter? = null
    private val tfLock = Any()

    private fun detector(): FaceDetector {
        detector?.let { return it }
        val d = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
        )
        detector = d
        return d
    }

    private fun interpreter(ctx: Context): Interpreter {
        tflite?.let { return it }
        val fd = ctx.assets.openFd("MobileFaceNet.tflite")
        val buf = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val ip = Interpreter(buf, Interpreter.Options().setNumThreads(2))
        tflite = ip
        return ip
    }

    /** Les visages d'une image, recadrés, du plus grand au plus petit. */
    fun faces(bmp: Bitmap): List<Bitmap> {
        // Téléphone tenu de travers : on essaie aussi l'image tournée.
        for (rot in intArrayOf(0, 90, 270)) {
            val b = if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
                android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true)
            val r = facesUpright(b)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    private fun facesUpright(bmp: Bitmap): List<Bitmap> {
        val faces = Tasks.await(detector().process(InputImage.fromBitmap(bmp, 0)), 10, TimeUnit.SECONDS)
        return faces.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }.mapNotNull { f ->
            val r = f.boundingBox
            val px = (r.width() * 0.15f).toInt()
            val py = (r.height() * 0.15f).toInt()
            val l = max(0, r.left - px)
            val t = max(0, r.top - py)
            val rr = min(bmp.width, r.right + px)
            val bb = min(bmp.height, r.bottom + py)
            if (rr - l < 40 || bb - t < 40) null else Bitmap.createBitmap(bmp, l, t, rr - l, bb - t)
        }
    }

    fun embedFace(ctx: Context, face: Bitmap): FloatArray = synchronized(tfLock) {
        val ip = interpreter(ctx)
        val inShape = ip.getInputTensor(0).shape()
        val outShape = ip.getOutputTensor(0).shape()
        require(inShape.size == 4 && inShape[3] == 3) { "modèle de visage inattendu : ${inShape.joinToString("x")}" }
        val batch = inShape[0]
        val size = inShape[1]
        val sc = Bitmap.createScaledBitmap(face, size, size, true)
        val px = IntArray(size * size)
        sc.getPixels(px, 0, size, 0, 0, size, size)
        val one = Array(size) { y ->
            Array(size) { x ->
                val p = px[y * size + x]
                floatArrayOf(
                    (((p shr 16) and 255) - 127.5f) / 128f,
                    (((p shr 8) and 255) - 127.5f) / 128f,
                    ((p and 255) - 127.5f) / 128f
                )
            }
        }
        val input = Array(batch) { one }
        val output = Array(outShape[0]) { FloatArray(outShape[1]) }
        ip.run(input, output)
        l2(output[0])
    }

    /** Enregistre le visage du propriétaire à partir de plusieurs photos. Renvoie un message pour la personne. */
    fun enrollFace(ctx: Context, jpegs: List<ByteArray>): String {
        val embs = ArrayList<FloatArray>()
        var multiple = false
        for (j in jpegs) {
            val bmp = BitmapFactory.decodeByteArray(j, 0, j.size) ?: continue
            val f = faces(bmp)
            if (f.isEmpty()) continue
            if (f.size > 1) multiple = true
            embs += embedFace(ctx, f[0])
        }
        if (embs.size < 2) return "Visage non détecté sur assez de photos (${embs.size} sur ${jpegs.size}). Regarde bien la caméra avant, dans un endroit éclairé."
        save(ctx, "face_emb", average(embs))
        return "Visage enregistré (${embs.size} photos)." + if (multiple) " Attention : plusieurs visages étaient visibles, le plus grand a été gardé." else ""
    }

    /** Résultat d'un coup d'œil : combien de visages, le propriétaire en fait-il partie, combien d'autres. */
    class Scan(val total: Int, val owner: Boolean, val others: Int, val best: Float)

    fun analyze(ctx: Context, jpeg: ByteArray): Scan {
        val own = load(ctx, "face_emb")
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return Scan(0, false, 0, 0f)
        // Les visages minuscules (passants au loin) sont ignorés ; celui d'un curieux derrière l'épaule reste assez grand.
        val f = faces(bmp).filter { it.width >= 70 }.take(5)
        if (f.isEmpty()) return Scan(0, false, 0, 0f)
        if (own == null) return Scan(f.size, false, f.size, 0f)
        var owner = false
        var others = 0
        var best = -1f
        for (face in f) {
            val c = cos(own, embedFace(ctx, face))
            best = max(best, c)
            if (c >= FACE_THRESHOLD) owner = true else others++
        }
        return Scan(f.size, owner, others, best)
    }

    fun checkFace(ctx: Context, jpeg: ByteArray): Result {
        val own = load(ctx, "face_emb") ?: return Result(Kind.NONE, 0f)
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return Result(Kind.NONE, 0f)
        val f = faces(bmp)
        if (f.isEmpty()) return Result(Kind.NONE, 0f)
        var best = -1f
        for (face in f.take(4)) best = max(best, cos(own, embedFace(ctx, face)))
        return Result(if (best >= FACE_THRESHOLD) Kind.OWNER else Kind.OTHER, best)
    }

    // ---------- Voix ----------

    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null
    private val voiceLock = Any()

    private fun extractor(ctx: Context): SpeakerEmbeddingExtractor {
        extractor?.let { return it }
        val e = SpeakerEmbeddingExtractor(
            assetManager = ctx.assets,
            config = SpeakerEmbeddingExtractorConfig(model = "wespeaker_en_voxceleb_resnet34.onnx", numThreads = 2)
        )
        extractor = e
        return e
    }

    /** Enregistre [seconds] secondes de micro. Renvoie null si le micro est refusé. */
    @Suppress("MissingPermission")
    fun record(seconds: Int): FloatArray? {
        val rate = 16000
        val total = rate * seconds
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192))
        } catch (_: Exception) { return null }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val pcm = ShortArray(total)
        try {
            rec.startRecording()
            var n = 0
            while (n < total) {
                val r = rec.read(pcm, n, min(4096, total - n))
                if (r <= 0) break
                n += r
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        return FloatArray(total) { pcm[it] / 32768f }
    }

    fun rms(s: FloatArray): Float {
        if (s.isEmpty()) return 0f
        var a = 0.0
        for (x in s) a += x * x
        return sqrt(a / s.size).toFloat()
    }

    fun embedVoice(ctx: Context, samples: FloatArray): FloatArray? = synchronized(voiceLock) {
        val ex = extractor(ctx)
        val st = ex.createStream()
        try {
            st.acceptWaveform(samples, 16000)
            st.inputFinished()
            if (!ex.isReady(st)) null else l2(ex.compute(st))
        } finally {
            st.release()
        }
    }

    fun enrollVoice(ctx: Context): String {
        val s = record(7) ?: return "Micro refusé : autorise le micro pour Antivol."
        if (rms(s) < 0.01f) return "Je n'ai rien entendu. Parle normalement pendant les 7 secondes."
        val e = embedVoice(ctx, s) ?: return "Voix trop courte, réessaie en parlant sans t'arrêter."
        save(ctx, "voice_emb", e)
        return "Voix enregistrée."
    }

    /** Écoute [seconds] secondes : NONE s'il n'y a pas de parole, sinon OWNER ou OTHER. */
    fun checkVoice(ctx: Context, seconds: Int = 4): Result {
        val own = load(ctx, "voice_emb") ?: return Result(Kind.NONE, 0f)
        val s = record(seconds) ?: return Result(Kind.NONE, 0f)
        if (rms(s) < 0.015f) return Result(Kind.NONE, 0f)
        val e = embedVoice(ctx, s) ?: return Result(Kind.NONE, 0f)
        val c = cos(own, e)
        return Result(if (c >= VOICE_THRESHOLD) Kind.OWNER else Kind.OTHER, c)
    }
}
