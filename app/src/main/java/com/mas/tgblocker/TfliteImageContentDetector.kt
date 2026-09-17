package com.mas.tgblocker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Implementasi [ImageContentDetector] pakai TensorFlow Lite.
 *
 * STATUS MODEL: project ini TIDAK membundel file model apa pun. Kelas ini
 * mencari file di [MODEL_ASSET_PATH] ("content_detector.tflite") di dalam
 * assets SAAT RUNTIME. Kalau tidak ketemu, [isAvailable] langsung false dan
 * [detect] selalu memanggil callback dengan [ImageContentDetector.SCORE_UNAVAILABLE]
 * — TIDAK PERNAH pura-pura menghasilkan skor kalau sebenarnya tidak ada model.
 *
 * CARA PASANG MODEL SUNGGUHAN (baca juga ringkasan chat untuk link/sumbernya):
 * 1. Sumber yang direkomendasikan: model TFLite hasil port Yahoo open_nsfw
 *    (lisensi BSD-3-Clause pada model aslinya; banyak port Android open-source
 *    memakai file model yang sama, ukuran ~6MB, input 224x224 RGB, output
 *    2 kelas [sfw, nsfw]).
 * 2. Taruh file itu persis di: app/src/main/assets/content_detector.tflite
 * 3. Di app/build.gradle.kts, pastikan blok `androidResources { noCompress += "tflite" }`
 *    ada (sudah ditambahkan) supaya file model tidak ikut terkompresi APK
 *    (kalau terkompresi, TFLite Interpreter gagal buka file-nya).
 * 4. Kalau model yang kamu pasang PUNYA BENTUK OUTPUT BEDA (misal 5 kelas
 *    gaya GantMan/nsfw_model: drawings/hentai/neutral/porn/sexy), ubah
 *    [OUTPUT_MODE] ke [OutputMode.FIVE_CLASS_GANTMAN] dan sesuaikan urutan
 *    label di [FIVE_CLASS_LABELS] persis urutan output model itu.
 *
 * PENTING — INI ASUMSI, BUKAN VERIFIKASI: karena aku (asisten) tidak
 * pernah memegang file model sungguhan, angka INPUT_SIZE/MEAN/STD dan
 * urutan output di bawah ini berdasar dokumentasi publik proyek-proyek
 * open_nsfw-TFLite yang aku temukan, BUKAN dicek langsung dari file
 * biner-nya. Kalau ternyata meleset (mis. Interpreter error shape
 * mismatch saat run), sesuaikan konstanta di companion object ini —
 * jangan asumsikan kodenya salah total, biasanya cuma beda ukuran input
 * atau urutan output.
 */
class TfliteImageContentDetector(context: Context) : ImageContentDetector {

    private enum class OutputMode { SFW_NSFW_BINARY, FIVE_CLASS_GANTMAN }

    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var interpreter: Interpreter? = null
    private val modelAvailable: Boolean

    init {
        modelAvailable = tryLoadModel()
    }

    override fun isAvailable(): Boolean = modelAvailable

    private fun tryLoadModel(): Boolean {
        return try {
            val assetFd = appContext.assets.openFd(MODEL_ASSET_PATH)
            val inputStream = FileInputStream(assetFd.fileDescriptor)
            val fileChannel = inputStream.channel
            val mappedBuffer: MappedByteBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                assetFd.startOffset,
                assetFd.declaredLength
            )
            interpreter = Interpreter(mappedBuffer)
            Log.i(TAG, "Model content-detector berhasil dimuat dari assets/$MODEL_ASSET_PATH")
            true
        } catch (e: Exception) {
            // Ini BUKAN error yang perlu bikin app crash — ini kondisi normal
            // kalau pengguna belum memasang file model. Log info saja, bukan error.
            Log.i(TAG, "Model content-detector belum terpasang (assets/$MODEL_ASSET_PATH tidak ditemukan) — deteksi gambar nonaktif")
            false
        }
    }

    override fun detect(bitmap: Bitmap, callback: (score: Float) -> Unit) {
        val currentInterpreter = interpreter
        if (!modelAvailable || currentInterpreter == null) {
            callback(ImageContentDetector.SCORE_UNAVAILABLE)
            return
        }

        // Inference dijalankan di background thread milik detector ini
        // sendiri — TIDAK PERNAH di thread accessibility event, supaya
        // tidak memblokir/nge-lag proses lain di service.
        executor.execute {
            val score = try {
                runInference(currentInterpreter, bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error saat inference content-detector, dianggap tidak tersedia", e)
                ImageContentDetector.SCORE_UNAVAILABLE
            }
            callback(score)
        }
    }

    private fun runInference(interpreter: Interpreter, bitmap: Bitmap): Float {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val inputBuffer = bitmapToByteBuffer(resized)

        return when (OUTPUT_MODE) {
            OutputMode.SFW_NSFW_BINARY -> {
                val output = Array(1) { FloatArray(2) } // [sfw, nsfw]
                interpreter.run(inputBuffer, output)
                output[0][1] // indeks 1 = probabilitas NSFW
            }
            OutputMode.FIVE_CLASS_GANTMAN -> {
                val output = Array(1) { FloatArray(5) }
                interpreter.run(inputBuffer, output)
                val scores = output[0]
                // Risk score = jumlah kelas berisiko (porn, hentai, sexy),
                // di-cap ke 1.0. "drawings" dan "neutral" tidak dihitung.
                RISK_CLASS_INDEXES.sumOf { idx -> scores.getOrElse(idx) { 0f }.toDouble() }
                    .toFloat().coerceIn(0f, 1f)
            }
        }
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            buffer.putFloat((r - MEAN) / STD)
            buffer.putFloat((g - MEAN) / STD)
            buffer.putFloat((b - MEAN) / STD)
        }
        buffer.rewind()
        return buffer
    }

    override fun close() {
        try {
            interpreter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error saat menutup interpreter", e)
        }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "ContentDetector"
        private const val MODEL_ASSET_PATH = "content_detector.tflite"

        // --- Sesuaikan kalau model yang kamu pasang beda spesifikasi ---
        private const val INPUT_SIZE = 224 // lebar/tinggi input model, dalam piksel
        private const val MEAN = 127.5f    // normalisasi: (pixel - MEAN) / STD
        private const val STD = 127.5f     // default ini -> rentang -1..1
        private val OUTPUT_MODE = OutputMode.SFW_NSFW_BINARY
        // Kalau OUTPUT_MODE = FIVE_CLASS_GANTMAN, urutan label diasumsikan
        // alfabetis (drawings=0, hentai=1, neutral=2, porn=3, sexy=4) sesuai
        // urutan yang dipakai proyek GantMan/nsfw_model.
        private val FIVE_CLASS_LABELS = listOf("drawings", "hentai", "neutral", "porn", "sexy")
        private val RISK_CLASS_INDEXES = listOf(
            FIVE_CLASS_LABELS.indexOf("hentai"),
            FIVE_CLASS_LABELS.indexOf("porn"),
            FIVE_CLASS_LABELS.indexOf("sexy")
        )
    }
}
