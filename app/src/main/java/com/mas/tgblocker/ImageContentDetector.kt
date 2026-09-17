package com.mas.tgblocker

import android.graphics.Bitmap

/**
 * Abstraksi detector konten gambar (NSFW/content-risk), supaya model/
 * implementasi bisa diganti nanti TANPA membongkar
 * TelegramBlockAccessibilityService — service cuma bicara ke interface ini.
 *
 * Kontrak:
 * - [detect] WAJIB 100% on-device. Implementasi tidak boleh mengirim
 *   bitmap ke server/API manapun.
 * - [detect] berjalan ASYNC (callback), karena inference bisa makan waktu
 *   puluhan-ratusan ms dan TIDAK BOLEH dijalankan di main thread/thread
 *   accessibility event (bisa bikin ANR).
 * - callback dipanggil dengan skor 0.0f..1.0f (makin tinggi = makin
 *   berisiko), ATAU [SCORE_UNAVAILABLE] kalau detector tidak punya model
 *   yang siap pakai (bukan skor 0.0 — 0.0 secara keliru berarti "pasti
 *   aman", padahal yang benar adalah "tidak bisa dinilai").
 * - [close] melepas resource native (mis. TFLite Interpreter) saat
 *   service dihentikan.
 */
interface ImageContentDetector {
    /** True kalau detector ini punya model sungguhan yang siap dipakai. */
    fun isAvailable(): Boolean

    fun detect(bitmap: Bitmap, callback: (score: Float) -> Unit)
    fun close()

    companion object {
        /** Nilai skor kalau detector tidak tersedia (model belum terpasang). */
        const val SCORE_UNAVAILABLE = -1f
    }
}
