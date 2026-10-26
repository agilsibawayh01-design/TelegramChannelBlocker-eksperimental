package com.mas.tgblocker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Detektor bagian tubuh terbuka (NudeNet 320n, YOLOv8n, 18 kelas) via ONNX Runtime.
 * Model: assets/nudenet_320n.onnx (input "images" [1,3,320,320] RGB 0..1,
 * output "output0" [1,22,2100] = 4 kotak + 18 skor kelas; sudah diverifikasi
 * bentuknya dengan onnxruntime). Lisensi bobot: AGPL-3.0 (Ultralytics).
 *
 * Skor = confidence tertinggi di antara kelas BERISIKO yang TERBUKA:
 * bokong, payudara wanita, alat kelamin wanita/pria, anus.
 * Wajah, kaki, ketiak, perut, dada pria, dan semua kelas "COVERED" diabaikan.
 *
 * Layar ponsel yang tinggi dipotong jadi beberapa kotak persegi (maks 4) supaya
 * objek kecil tidak mengecil jadi tidak terdeteksi.
 */
class OnnxBodyPartDetector(context: Context) : ImageContentDetector {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val available: Boolean

    init {
        available = try {
            val bytes = context.applicationContext.assets.open(MODEL_ASSET).use { it.readBytes() }
            val e = OrtEnvironment.getEnvironment()
            env = e
            session = e.createSession(bytes, OrtSession.SessionOptions())
            true
        } catch (e: Throwable) {
            Log.i(TAG, "Model NudeNet tidak tersedia: ${e.javaClass.simpleName}")
            false
        }
    }

    override fun isAvailable(): Boolean = available

    override fun detect(bitmap: Bitmap, callback: (score: Float) -> Unit) {
        if (!available) {
            callback(ImageContentDetector.SCORE_UNAVAILABLE)
            return
        }
        executor.execute {
            val score = try {
                run(bitmap)
            } catch (e: Throwable) {
                Log.e(TAG, "Error inference NudeNet", e)
                ImageContentDetector.SCORE_UNAVAILABLE
            }
            callback(score)
        }
    }

    private fun run(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val tiles = mutableListOf<Rect>()
        if (h > w) {
            val n = minOf(MAX_TILES, (h + w - 1) / w)
            if (n <= 1) {
                tiles.add(Rect(0, 0, w, h))
            } else {
                val step = (h - w).toFloat() / (n - 1)
                for (i in 0 until n) {
                    val top = (i * step).toInt()
                    tiles.add(Rect(0, top, w, top + w))
                }
            }
        } else {
            tiles.add(Rect(0, 0, w, h))
        }
        var best = 0f
        for (tile in tiles) best = maxOf(best, inferTile(bitmap, tile))
        return best
    }

    private fun inferTile(src: Bitmap, region: Rect): Float {
        val e = env ?: return ImageContentDetector.SCORE_UNAVAILABLE
        val s = session ?: return ImageContentDetector.SCORE_UNAVAILABLE

        // Letterbox: skala proporsional ke 320x320, sisanya hitam.
        val scale = SIZE.toFloat() / maxOf(region.width(), region.height())
        val dw = (region.width() * scale).toInt().coerceAtLeast(1)
        val dh = (region.height() * scale).toInt().coerceAtLeast(1)
        val canvasBmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.BLACK)
        val dst = Rect((SIZE - dw) / 2, (SIZE - dh) / 2, (SIZE - dw) / 2 + dw, (SIZE - dh) / 2 + dh)
        canvas.drawBitmap(src, region, dst, null)

        val pixels = IntArray(SIZE * SIZE)
        canvasBmp.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        canvasBmp.recycle()

        val plane = SIZE * SIZE
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            data[i] = ((p shr 16) and 0xFF) / 255f           // R
            data[plane + i] = ((p shr 8) and 0xFF) / 255f    // G
            data[2 * plane + i] = (p and 0xFF) / 255f        // B
        }

        OnnxTensor.createTensor(e, FloatBuffer.wrap(data), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
            s.run(mapOf("images" to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = (result[0].value as Array<Array<FloatArray>>)[0] // [22][N]
                var best = 0f
                for (cls in RISK_CLASSES) {
                    val row = out[4 + cls]
                    for (v in row) if (v > best) best = v
                }
                return best
            }
        }
    }

    override fun close() {
        try { session?.close() } catch (_: Throwable) {}
        executor.shutdown()
    }

    companion object {
        private const val TAG = "ContentDetector"
        private const val MODEL_ASSET = "nudenet_320n.onnx"
        private const val SIZE = 320
        private const val MAX_TILES = 4

        // 2 BUTTOCKS_EXPOSED, 3 FEMALE_BREAST_EXPOSED, 4 FEMALE_GENITALIA_EXPOSED,
        // 6 ANUS_EXPOSED, 14 MALE_GENITALIA_EXPOSED
        private val RISK_CLASSES = intArrayOf(2, 3, 4, 6, 14)
    }
}
