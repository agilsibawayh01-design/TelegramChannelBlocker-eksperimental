package com.mas.tgblocker

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Deteksi Global Search Telegram — dipindahkan dari aplikasi Safe Telegram
 * (versi fungsional "SafeTelegram 24", kelas TelegramGuardService).
 *
 * Logika dan daftar penanda SENGAJA dipertahankan sama persis dengan versi
 * yang sudah terbukti berfungsi. Kalau Telegram mengubah tampilannya dan
 * deteksi berhenti cocok, cukup ubah dua daftar di bawah:
 * [SEARCH_ENTRY_ID_HINTS] dan [GLOBAL_RESULT_SIGNATURES].
 *
 * Objek ini hanya mendeteksi. Aksi (BACK, debounce, jadwal cek ulang setelah
 * klik) ada di [TelegramBlockAccessibilityService].
 */
object GlobalSearchGuard {

    // Fragmen resource-id pada kolom/ikon pencarian di daftar chat Telegram.
    // Dicocokkan dengan "contains" karena Telegram menambah akhiran dinamis.
    val SEARCH_ENTRY_ID_HINTS = listOf(
        "search_edit_text",
        "chats_search",
        "action_bar_search"
    )

    // Judul bagian yang HANYA muncul kalau hasil pencarian memuat entitas
    // publik (global). Pencarian di dalam satu chat tidak menampilkannya.
    val GLOBAL_RESULT_SIGNATURES = listOf(
        "global search",
        "pencarian global",
        "public channel",
        "channel publik",
        "global"
    )

    // Jeda antar BACK supaya tidak menembak berkali-kali untuk satu perubahan layar.
    const val BACK_DEBOUNCE_MS = 400L

    // Jeda sebelum mengecek ulang setelah klik ikon pencarian, memberi waktu
    // Telegram menggambar layar pencarian.
    const val POST_CLICK_CHECK_DELAY_MS = 180L

    // BACK kedua setelah jeda singkat, kalau Telegram butuh dua kali pop
    // (tutup keyboard + tutup fragment pencarian).
    const val SECOND_BACK_DELAY_MS = 120L

    private const val MAX_DEPTH = 40

    /** Apakah node yang diklik terlihat seperti pintu masuk pencarian di daftar chat. */
    fun isSearchEntryPoint(node: AccessibilityNodeInfo): Boolean {
        val resId = node.viewIdResourceName?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        if (SEARCH_ENTRY_ID_HINTS.any { resId.contains(it) }) return true
        if (desc == "search" || desc.contains("cari")) return true
        return false
    }

    /** Apakah layar yang sedang tampil memuat penanda hasil Global Search. */
    fun containsGlobalSearchSignature(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > MAX_DEPTH) return false // pengaman untuk pohon tampilan yang aneh

        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()
        val resId = node.viewIdResourceName?.lowercase()

        if (text != null && GLOBAL_RESULT_SIGNATURES.any { text == it || text.startsWith(it) }) {
            return true
        }
        if (desc != null && GLOBAL_RESULT_SIGNATURES.any { desc.contains(it) }) {
            return true
        }
        if (resId != null && (resId.contains("global_search") || resId.contains("public_search"))) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = try {
                containsGlobalSearchSignature(child, depth + 1)
            } finally {
                child.recycle()
            }
            if (found) return true
        }
        return false
    }
}
