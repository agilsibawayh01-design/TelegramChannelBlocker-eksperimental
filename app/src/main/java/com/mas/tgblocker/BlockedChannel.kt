package com.mas.tgblocker

enum class ChannelType {
    USERNAME,   // dicocokkan lewat pola "@username" atau "t.me/username"
    NAME        // dicocokkan lewat nama tampilan PERSIS (case-sensitive)
}

/**
 * Merepresentasikan satu entri channel/bot Telegram yang diblokir.
 *
 * - [ChannelType.USERNAME]: [value] tanpa "@" di depan. Cocok lewat pola
 *   "@username" (biasanya muncul di halaman Profil/Info) atau "t.me/username"
 *   (biasanya muncul di field Link halaman info channel).
 * - [ChannelType.NAME]: [value] adalah nama tampilan APA ADANYA (case-sensitive).
 *   Ini yang muncul di toolbar chat SEJAK CHAT DIBUKA, tanpa perlu buka Profil.
 */
data class BlockedChannel(
    val type: ChannelType,
    val value: String
) {
    fun normalized(): String = value.trim().lowercase()

    fun display(): String = when (type) {
        ChannelType.USERNAME -> "@${value.trim()}"
        ChannelType.NAME -> value.trim()
    }
}
