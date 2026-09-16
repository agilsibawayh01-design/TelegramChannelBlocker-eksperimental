package com.mas.tgblocker

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Mendeteksi apakah layar Telegram saat ini sedang menampilkan salah satu
 * channel/bot yang ada di daftar blokir.
 *
 * Dua mode pencocokan:
 * - USERNAME: pola "@username" (biasa muncul di halaman Profil/Info) ATAU
 *   "t.me/username"/"telegram.me/username" (biasa muncul di field Link
 *   halaman info channel). Tidak case-sensitive, pakai word-boundary.
 * - NAME: kecocokan PERSIS (case-sensitive, exact match per node) pada nama
 *   tampilan — ini yang muncul di toolbar SEJAK CHAT DIBUKA, tanpa perlu
 *   buka Profil dulu. Sengaja dibuat ketat (exact match) supaya tidak salah
 *   blokir chat lain yang kebetulan judulnya mirip.
 */
object ChannelDetector {

    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 800

    data class ScreenTexts(
        val normalized: List<String>, // lowercase, untuk pencocokan USERNAME
        val raw: List<String>         // apa adanya (trim + rapikan whitespace saja), untuk NAME
    )

    /** Mengumpulkan semua teks (text + contentDescription) yang tampil di layar. */
    fun collectTexts(root: AccessibilityNodeInfo?): ScreenTexts {
        if (root == null) return ScreenTexts(emptyList(), emptyList())
        val normalized = mutableListOf<String>()
        val raw = mutableListOf<String>()
        val visited = HashSet<AccessibilityNodeInfo>()
        traverse(root, depth = 0, visited = visited, normalizedOut = normalized, rawOut = raw)
        return ScreenTexts(normalized, raw)
    }

    private fun traverse(
        node: AccessibilityNodeInfo?,
        depth: Int,
        visited: MutableSet<AccessibilityNodeInfo>,
        normalizedOut: MutableList<String>,
        rawOut: MutableList<String>
    ) {
        if (node == null) return
        if (depth > MAX_DEPTH) return
        if (normalizedOut.size > MAX_NODES) return
        if (!visited.add(node)) return

        node.text?.let { t -> addText(t.toString(), normalizedOut, rawOut) }
        node.contentDescription?.let { cd -> addText(cd.toString(), normalizedOut, rawOut) }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (normalizedOut.size > MAX_NODES) return
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            traverse(child, depth + 1, visited, normalizedOut, rawOut)
        }
    }

    private fun addText(text: String, normalizedOut: MutableList<String>, rawOut: MutableList<String>) {
        val cleaned = tidyWhitespace(text)
        if (cleaned.isBlank()) return
        rawOut.add(cleaned)
        normalizedOut.add(cleaned.lowercase())
    }

    private fun tidyWhitespace(text: String): String {
        return text.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Mengecek apakah salah satu teks di layar mengandung referensi eksplisit
     * ke username channel, baik lewat pola "@username" maupun "t.me/username".
     */
    fun containsBlockedUsername(normalizedTexts: List<String>, blocked: List<BlockedChannel>): BlockedChannel? {
        val usernameEntries = blocked.filter { it.type == ChannelType.USERNAME }
        for (channel in usernameEntries) {
            val target = channel.normalized()
            if (target.isBlank()) continue

            val escaped = Regex.escape(target)
            val patternAt = Regex("(^|[^a-z0-9_])@$escaped([^a-z0-9_]|$)")
            val patternLink = Regex("(^|[^a-z0-9_./])(https?://)?(www\\.)?(t\\.me|telegram\\.me)/$escaped([^a-z0-9_]|$)")

            for (text in normalizedTexts) {
                if (patternAt.containsMatchIn(text) || patternLink.containsMatchIn(text)) {
                    return channel
                }
            }
        }
        return null
    }

    /**
     * Mengecek apakah salah satu teks di layar MENGANDUNG nama tampilan yang
     * diblokir (case-sensitive, tapi boleh ada karakter tambahan di sekitarnya
     * seperti emoji/ikon status — umum di Telegram, misal "🔴 Infokomando").
     */
    fun containsBlockedChannelName(rawTexts: List<String>, blocked: List<BlockedChannel>): BlockedChannel? {
        val nameEntries = blocked.filter { it.type == ChannelType.NAME }
        for (channel in nameEntries) {
            val target = channel.value.trim()
            if (target.isBlank()) continue
            if (rawTexts.any { it.contains(target) }) {
                return channel
            }
        }
        return null
    }
}
