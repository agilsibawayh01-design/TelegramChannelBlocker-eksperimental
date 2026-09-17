package com.mas.tgblocker

import android.content.Context

/**
 * Titik ganti tunggal untuk [ImageContentDetector]. Kalau nanti mau ganti
 * model/implementasi (mis. model 5-kelas, atau library berbeda), cukup
 * ubah satu baris di sini — TelegramBlockAccessibilityService tidak perlu
 * disentuh sama sekali karena cuma bicara ke interface [ImageContentDetector].
 */
object ImageContentDetectorFactory {
    @Volatile
    private var instance: ImageContentDetector? = null

    fun get(context: Context): ImageContentDetector {
        return instance ?: synchronized(this) {
            instance ?: TfliteImageContentDetector(context.applicationContext).also { instance = it }
        }
    }
}
