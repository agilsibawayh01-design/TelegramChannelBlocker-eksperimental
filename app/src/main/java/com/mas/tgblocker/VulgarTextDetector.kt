package com.mas.tgblocker

/**
 * Deteksi teks vulgar/NSFW dari kumpulan teks yang tampil di layar (hasil
 * baca Accessibility API — bukan OCR gambar).
 *
 * Pakai sistem confidence/threshold biar tidak langsung BLOCK cuma karena
 * satu kata biasa nyasar (mengurangi false positive), sesuai permintaan:
 * - Kata di [strongTerms]: sinyal kuat, cukup SATU kemunculan untuk block.
 * - Kata di [moderateTerms]: sinyal sedang, butuh DUA ATAU LEBIH kemunculan
 *   (kata berbeda, bukan kata yang sama diulang) untuk block.
 *
 * PENTING — batasan yang jujur (sama seperti AdultDomainList):
 * - Ini daftar yang sudah diperluas, TAPI TETAP BUKAN kamus lengkap. Banyak
 *   variasi slang/typo yang sengaja dibuat untuk menghindari filter kata
 *   tidak akan tertangkap, dan bahasa terus berubah.
 * - Matching berbasis word-boundary pada teks yang SUDAH tampil di layar
 *   lewat Accessibility API — tidak ada OCR teks-dalam-gambar di sini.
 * - Pengguna tidak bisa menambah/mengubah daftar kata ini dari UI (beda
 *   dengan domain/package) supaya daftar kata vulgar tidak perlu
 *   ditampilkan mentah-mentah di layar aplikasi.
 */
object VulgarTextDetector {

    data class Result(
        val matchedCount: Int,
        val confidence: Double, // 0.0 - 1.0
        val isBlocked: Boolean
    )

    private val strongTerms = setOf(
        // Indonesia
        "porno", "pornografi", "bokep", "ngentot", "memek", "kontol",
        "ngewe", "colmek", "pepek", "pelacur",
        // Inggris / umum
        "porn", "pornhub", "xvideos", "xnxx", "xxx", "hentai",
        "cumshot", "blowjob", "handjob", "gangbang", "creampie", "anal",
        "dildo", "prostitute", "whore", "hooker"
    )

    private val moderateTerms = setOf(
        // Indonesia
        "seks", "telanjang", "bugil", "vulgar", "cabul", "mesum",
        "toket",
        // Inggris / umum
        "sex", "nude", "nudes", "striptease", "escort", "milf", "boobs",
        "tits", "cum", "orgasm", "masturbate", "fetish", "slut", "nipple",
        "orgy", "incest", "rape"
    )

    private val wordPattern = Regex("[a-z0-9]+")

    fun analyze(texts: List<String>): Result {
        if (texts.isEmpty()) return Result(0, 0.0, false)

        val joined = texts.joinToString(" ").lowercase()
        val words = wordPattern.findAll(joined).map { it.value }.toSet()

        val strongHits = strongTerms.count { it in words }
        val moderateHits = moderateTerms.count { it in words }

        val isBlocked = strongHits >= 1 || moderateHits >= 2
        val rawScore = (strongHits * 1.0) + (moderateHits * 0.4)
        val confidence = (rawScore / 2.0).coerceIn(0.0, 1.0)

        return Result(strongHits + moderateHits, confidence, isBlocked)
    }
}
