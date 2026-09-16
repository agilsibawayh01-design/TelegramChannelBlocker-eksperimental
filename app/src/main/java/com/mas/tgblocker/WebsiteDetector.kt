package com.mas.tgblocker

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Mendeteksi apakah URL/domain yang sedang aktif di address bar Chrome
 * termasuk dalam daftar blokir (domain custom milik pengguna ATAU domain
 * dewasa bawaan).
 *
 * Pendekatan:
 * 1. Coba cari node address bar Chrome secara spesifik lewat resource-id
 *    ("com.android.chrome:id/url_bar"). Ini paling akurat.
 * 2. Kalau tidak ketemu (versi Chrome beda / UI berubah), fallback: cari
 *    pola domain di SEMUA teks pada layar.
 *
 * PENTING soal akurasi: ini murni text-matching dari apa yang tampil di
 * layar lewat Accessibility API (bukan intercept jaringan/DNS), jadi:
 * - Bisa saja tidak 100% akurat kalau Chrome mengubah struktur UI-nya.
 * - Domain dicocokkan berdasarkan HOST, bukan sekadar "mengandung teks",
 *   supaya "example.com" tidak salah blokir "notexample.com" atau
 *   "example.com.evil.net" (kecuali memang subdomain sah dari domain itu).
 */
object WebsiteDetector {

    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 800
    private const val CHROME_URL_BAR_ID = "com.android.chrome:id/url_bar"

    /** Mengambil teks address bar Chrome (kalau ketemu by id), atau null kalau tidak. */
    fun findUrlBarText(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        return try {
            val nodes = root.findAccessibilityNodeInfosByViewId(CHROME_URL_BAR_ID)
            nodes?.firstOrNull()?.let { node ->
                val text = (node.text ?: node.contentDescription)?.toString()
                text
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Fallback: kumpulkan semua teks di layar (dipakai kalau url bar tidak ketemu). */
    fun collectAllTexts(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val out = mutableListOf<String>()
        val visited = HashSet<AccessibilityNodeInfo>()
        traverse(root, 0, visited, out)
        return out
    }

    private fun traverse(
        node: AccessibilityNodeInfo?,
        depth: Int,
        visited: MutableSet<AccessibilityNodeInfo>,
        out: MutableList<String>
    ) {
        if (node == null || depth > MAX_DEPTH || out.size > MAX_NODES) return
        if (!visited.add(node)) return

        node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }

        for (i in 0 until node.childCount) {
            if (out.size > MAX_NODES) return
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            traverse(child, depth + 1, visited, out)
        }
    }

    /**
     * Ambil kandidat host dari sebuah teks (URL lengkap ATAU cuma domain polos
     * yang diketik di address bar, mis. "youtube.com" tanpa skema).
     */
    private val hostPattern = Regex(
        "(?:https?://)?(?:www\\.)?([a-z0-9]([a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+)",
        RegexOption.IGNORE_CASE
    )

    private fun extractHosts(text: String): List<String> {
        return hostPattern.findAll(text).map { it.groupValues[1].lowercase() }.toList()
    }

    /** True kalau [host] sama persis dengan [target], atau subdomain sah dari [target]. */
    private fun hostMatches(host: String, target: String): Boolean {
        val t = target.trim().lowercase().removePrefix("www.")
        if (t.isBlank()) return false
        return host == t || host.endsWith(".$t")
    }

    /**
     * Cek apakah host di [text] cocok dengan salah satu domain di [blockedDomains]
     * (gabungan domain custom milik pengguna + domain dewasa bawaan).
     * Mengembalikan domain yang match, atau null.
     */
    fun findBlockedDomain(text: String?, blockedDomains: Collection<String>): String? {
        if (text.isNullOrBlank() || blockedDomains.isEmpty()) return null
        val hosts = extractHosts(text)
        for (host in hosts) {
            for (target in blockedDomains) {
                if (hostMatches(host, target)) return target
            }
        }
        return null
    }
}
