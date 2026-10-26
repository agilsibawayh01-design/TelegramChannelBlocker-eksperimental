package com.mas.tgblocker

import android.content.Context
import android.net.Uri
import android.os.SystemClock

/**
 * Membaca status "Mode OFF (jeda)" dari aplikasi Penjaga Ketat
 * (com.mas.strictguard) lewat provider hanya-baca miliknya. Kalau aplikasi itu
 * tidak terpasang / tidak menjawab, hasilnya false (tidak ada yang dikunci).
 * Hasil di-cache sebentar supaya tidak memanggil lintas-proses di setiap event.
 */
object PenjagaKetatLink {
    private val URI: Uri = Uri.parse("content://com.mas.strictguard.state/paused")
    private const val CACHE_MS = 1500L

    @Volatile private var lastAt = -CACHE_MS
    @Volatile private var lastValue = false

    fun isPaused(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < CACHE_MS) return lastValue
        lastAt = now
        lastValue = try {
            context.contentResolver.query(URI, null, null, null, null)?.use { c ->
                c.moveToFirst() && c.getInt(0) == 1
            } ?: false
        } catch (e: Exception) {
            false
        }
        return lastValue
    }
}
