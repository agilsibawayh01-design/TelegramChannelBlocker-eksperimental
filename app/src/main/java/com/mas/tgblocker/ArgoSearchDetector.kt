package com.mas.tgblocker

/**
 * Deteksi khusus untuk entri "Argo Search" di dalam Telegram, karena entri ini
 * tidak selalu muncul sebagai "@argo" polos — kadang tampil sebagai judul
 * "Argo Search" dengan subtitle "Telegram's search engine".
 *
 * PENTING (anti false-positive):
 * Deteksi HANYA dianggap valid jika signature ditemukan dalam SATU string
 * node yang sama (title atau title+subtitle yang digabung dari node yang
 * bersebelahan), bukan hanya karena kata "search" muncul di suatu tempat
 * di layar. Kata "search" sendirian TIDAK PERNAH memicu blokir.
 */
object ArgoSearchDetector {

    // "argo search" sebagai frasa utuh (dengan normalisasi whitespace)
    private val PHRASE_ARGO_SEARCH = Regex("\\bargo\\s+search\\b")

    // Subtitle khas yang menyertai entri Argo Search di pencarian Telegram
    private val PHRASE_SEARCH_ENGINE = Regex("telegram'?s\\s+search\\s+engine")

    /**
     * texts: daftar teks yang SUDAH dinormalisasi (lowercase) per-node,
     * dari ChannelDetector.collectNormalizedTexts().
     *
     * Mengembalikan true jika ditemukan kombinasi signature yang meyakinkan.
     */
    fun isArgoSearchPresent(texts: List<String>): Boolean {
        // 1) Frasa "argo search" langsung dalam satu node -> pasti
        if (texts.any { PHRASE_ARGO_SEARCH.containsMatchIn(it) }) return true

        // 2) Subtitle "Telegram's search engine" dalam satu node ATAU node bersebelahan,
        //    dikombinasikan dengan adanya kata "argo" di layar yang sama.
        val hasSearchEngineSubtitle = texts.any { PHRASE_SEARCH_ENGINE.containsMatchIn(it) }
        val hasArgoWord = texts.any { Regex("\\bargo\\b").containsMatchIn(it) }
        if (hasSearchEngineSubtitle && hasArgoWord) return true

        // Catatan: kata "search" saja (tanpa "argo" dan tanpa frasa lengkap di atas)
        // sengaja TIDAK dianggap match, untuk mencegah false positive.
        return false
    }
}
