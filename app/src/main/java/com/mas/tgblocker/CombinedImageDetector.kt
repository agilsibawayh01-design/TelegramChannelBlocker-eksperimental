package com.mas.tgblocker

import android.graphics.Bitmap

/**
 * Menggabungkan beberapa detektor gambar: skor akhir = skor TERTINGGI dari
 * detektor yang tersedia. Detektor yang tidak tersedia/gagal diabaikan; kalau
 * semuanya tidak tersedia hasilnya SCORE_UNAVAILABLE.
 */
class CombinedImageDetector(private val detectors: List<ImageContentDetector>) : ImageContentDetector {

    override fun isAvailable(): Boolean = detectors.any { it.isAvailable() }

    override fun detect(bitmap: Bitmap, callback: (score: Float) -> Unit) {
        val active = detectors.filter { it.isAvailable() }
        if (active.isEmpty()) {
            callback(ImageContentDetector.SCORE_UNAVAILABLE)
            return
        }
        runNext(active, 0, bitmap, ImageContentDetector.SCORE_UNAVAILABLE, callback)
    }

    private fun runNext(
        list: List<ImageContentDetector>, index: Int, bitmap: Bitmap,
        best: Float, callback: (Float) -> Unit
    ) {
        if (index >= list.size) {
            callback(best)
            return
        }
        list[index].detect(bitmap) { score ->
            val next = if (score != ImageContentDetector.SCORE_UNAVAILABLE && score > best) score else best
            runNext(list, index + 1, bitmap, next, callback)
        }
    }

    override fun close() {
        detectors.forEach { it.close() }
    }
}
