package com.mas.tgblocker

import android.graphics.Bitmap

/**
 * Menggabungkan beberapa detektor gambar: skor akhir = skor TERTINGGI dari
 * detektor yang tersedia. Detektor yang tidak tersedia/gagal diabaikan; kalau
 * semuanya tidak tersedia hasilnya SCORE_UNAVAILABLE.
 */
class CombinedImageDetector(private val detectors: List<ImageContentDetector>) : ImageContentDetector {

    /** Rincian skor per model dari pengecekan terakhir, mis. "lama=0.38 nudenet=0.79". */
    @Volatile var lastDetail: String = ""
        private set

    private fun nameOf(d: ImageContentDetector) =
        if (d is OnnxBodyPartDetector) "nudenet" else "lama"

    override fun isAvailable(): Boolean = detectors.any { it.isAvailable() }

    override fun detect(bitmap: Bitmap, callback: (score: Float) -> Unit) {
        val active = detectors.filter { it.isAvailable() }
        if (active.isEmpty()) {
            callback(ImageContentDetector.SCORE_UNAVAILABLE)
            return
        }
        runNext(active, 0, bitmap, ImageContentDetector.SCORE_UNAVAILABLE, callback, StringBuilder())
    }

    private fun runNext(
        list: List<ImageContentDetector>, index: Int, bitmap: Bitmap,
        best: Float, callback: (Float) -> Unit, detail: StringBuilder
    ) {
        if (index >= list.size) {
            lastDetail = detail.toString().trim()
            callback(best)
            return
        }
        list[index].detect(bitmap) { score ->
            val next = if (score != ImageContentDetector.SCORE_UNAVAILABLE && score > best) score else best
            detail.append(nameOf(list[index])).append('=').append(String.format(java.util.Locale.US, "%.2f", score)).append(' ')
            runNext(list, index + 1, bitmap, next, callback, detail)
        }
    }

    override fun close() {
        detectors.forEach { it.close() }
    }
}
