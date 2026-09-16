package com.mas.tgblocker

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class BlockingMode { OFF, NORMAL, STRICT }

/**
 * Menyimpan daftar channel yang diblokir dan mode pemblokiran secara lokal
 * di perangkat menggunakan SharedPreferences (tidak ada server/database online).
 *
 * Format: array JSON berisi objek {"type": "USERNAME"|"NAME"|"KEYWORD", "value": "..."}.
 * Data lama (array string polos, dari versi sebelum fitur tipe) tetap didukung
 * dan otomatis dikonversi jadi tipe USERNAME saat dibaca.
 */
class BlockedChannelRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(): BlockingMode {
        val stored = prefs.getString(KEY_MODE, null)
        if (stored != null) {
            return try {
                BlockingMode.valueOf(stored)
            } catch (e: Exception) {
                BlockingMode.NORMAL
            }
        }
        // Migrasi dari versi lama (boolean on/off): true -> NORMAL, false -> OFF.
        return if (prefs.getBoolean(KEY_ENABLED_LEGACY, true)) BlockingMode.NORMAL else BlockingMode.OFF
    }

    fun setMode(mode: BlockingMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getChannels(): List<BlockedChannel> {
        val raw = prefs.getString(KEY_CHANNELS, null) ?: return defaultChannels()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                when (val element = arr.get(i)) {
                    is JSONObject -> {
                        val typeStr = element.optString("type", "USERNAME")
                        val type = try {
                            ChannelType.valueOf(typeStr)
                        } catch (e: Exception) {
                            ChannelType.USERNAME
                        }
                        BlockedChannel(type, element.optString("value"))
                    }
                    is String -> BlockedChannel(ChannelType.USERNAME, element) // data lama
                    else -> null
                }
            }
        } catch (e: Exception) {
            defaultChannels()
        }
    }

    fun saveChannels(channels: List<BlockedChannel>) {
        val arr = JSONArray()
        channels.forEach { ch ->
            val obj = JSONObject()
            obj.put("type", ch.type.name)
            obj.put("value", ch.value)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_CHANNELS, arr.toString()).apply()
    }

    fun addChannel(type: ChannelType, rawValue: String): Boolean {
        val clean = if (type == ChannelType.USERNAME) {
            rawValue.trim().removePrefix("@")
        } else {
            rawValue.trim()
        }
        if (clean.isBlank()) return false

        val current = getChannels().toMutableList()
        val exists = current.any { existing ->
            existing.type == type && when (type) {
                ChannelType.USERNAME -> existing.normalized() == clean.lowercase()
                ChannelType.NAME -> existing.value == clean
            }
        }
        if (exists) return false
        current.add(BlockedChannel(type, clean))
        saveChannels(current)
        return true
    }

    fun updateChannel(old: BlockedChannel, newType: ChannelType, newRawValue: String): Boolean {
        val cleanNew = if (newType == ChannelType.USERNAME) {
            newRawValue.trim().removePrefix("@")
        } else {
            newRawValue.trim()
        }
        if (cleanNew.isBlank()) return false

        val current = getChannels().toMutableList()
        val index = current.indexOfFirst { it.type == old.type && it.value == old.value }
        if (index == -1) return false
        current[index] = BlockedChannel(newType, cleanNew)
        saveChannels(current)
        return true
    }

    fun deleteChannel(channel: BlockedChannel) {
        val current = getChannels().toMutableList()
        current.removeAll { it.type == channel.type && it.value == channel.value }
        saveChannels(current)
    }

    private fun defaultChannels(): List<BlockedChannel> =
        listOf(BlockedChannel(ChannelType.USERNAME, "argo"))

    // ----- Aplikasi/klon tambahan (custom packages) -----
    // Package di sini diperlakukan sama seperti CLONE_PACKAGES bawaan di
    // TelegramBlockAccessibilityService: langsung ditutup ke Home saat dibuka
    // (mengikuti mode saat ini), tanpa deteksi channel per-aplikasi.

    fun getCustomPackages(): List<String> {
        val raw = prefs.getString(KEY_CUSTOM_PACKAGES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCustomPackages(packages: List<String>) {
        val arr = JSONArray()
        packages.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { arr.put(it) }
        prefs.edit().putString(KEY_CUSTOM_PACKAGES, arr.toString()).apply()
    }

    /** Validasi longgar bentuk package name Android: dua segmen atau lebih, dipisah titik. */
    private val packageNamePattern =
        Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    fun addCustomPackage(rawValue: String): Boolean {
        val clean = rawValue.trim()
        if (clean.isBlank() || !packageNamePattern.matches(clean)) return false

        val current = getCustomPackages().toMutableList()
        val exists = current.any { it.equals(clean, ignoreCase = true) }
        if (exists) return false
        current.add(clean)
        saveCustomPackages(current)
        return true
    }

    fun deleteCustomPackage(pkg: String) {
        val current = getCustomPackages().toMutableList()
        current.removeAll { it.equals(pkg, ignoreCase = true) }
        saveCustomPackages(current)
    }

    // ----- Website yang Diblokir (domain custom) -----
    // Digabung dengan AdultDomainList (bawaan) saat dicek di service —
    // lihat TelegramBlockAccessibilityService. Yang disimpan di sini HANYA
    // domain custom milik pengguna, bukan daftar bawaan.

    fun getBlockedDomains(): List<String> {
        val raw = prefs.getString(KEY_BLOCKED_DOMAINS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveBlockedDomains(domains: List<String>) {
        val arr = JSONArray()
        domains.map { it.trim().lowercase().removePrefix("www.") }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { arr.put(it) }
        prefs.edit().putString(KEY_BLOCKED_DOMAINS, arr.toString()).apply()
    }

    /** Validasi longgar bentuk domain: minimal ada satu titik, karakter domain valid. */
    private val domainPattern =
        Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)+$")

    fun addBlockedDomain(rawValue: String): Boolean {
        val clean = rawValue.trim().lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .substringBefore("/")
        if (clean.isBlank() || !domainPattern.matches(clean)) return false

        val current = getBlockedDomains().toMutableList()
        if (current.any { it.equals(clean, ignoreCase = true) }) return false
        current.add(clean)
        saveBlockedDomains(current)
        return true
    }

    fun deleteBlockedDomain(domain: String) {
        val current = getBlockedDomains().toMutableList()
        current.removeAll { it.equals(domain, ignoreCase = true) }
        saveBlockedDomains(current)
    }

    // ----- Export / Import (backup teks lokal, tanpa server) -----

    fun exportData(): String {
        val obj = JSONObject()
        obj.put("version", 2)
        obj.put("mode", getMode().name)

        val channelsArr = JSONArray()
        getChannels().forEach { ch ->
            val o = JSONObject()
            o.put("type", ch.type.name)
            o.put("value", ch.value)
            channelsArr.put(o)
        }
        obj.put("channels", channelsArr)

        val pkgArr = JSONArray()
        getCustomPackages().forEach { pkgArr.put(it) }
        obj.put("customPackages", pkgArr)

        val domainArr = JSONArray()
        getBlockedDomains().forEach { domainArr.put(it) }
        obj.put("blockedDomains", domainArr)

        return obj.toString(2)
    }

    /**
     * Mengganti SELURUH channel, mode, aplikasi tambahan, dan domain blokir
     * dengan isi [json]. Mengembalikan false (tanpa mengubah apa pun) kalau
     * format tidak valid. Backward compatible dengan backup versi 1 (belum
     * ada "blockedDomains" — dianggap kosong).
     */
    fun importData(json: String): Boolean {
        return try {
            val obj = JSONObject(json)

            val modeStr = obj.optString("mode", BlockingMode.NORMAL.name)
            val mode = try {
                BlockingMode.valueOf(modeStr)
            } catch (e: Exception) {
                BlockingMode.NORMAL
            }

            val channelsArr = obj.optJSONArray("channels") ?: JSONArray()
            val channels = (0 until channelsArr.length()).mapNotNull { i ->
                val o = channelsArr.optJSONObject(i) ?: return@mapNotNull null
                val typeStr = o.optString("type", "USERNAME")
                val type = try {
                    ChannelType.valueOf(typeStr)
                } catch (e: Exception) {
                    ChannelType.USERNAME
                }
                val value = o.optString("value")
                if (value.isBlank()) null else BlockedChannel(type, value)
            }

            val pkgArr = obj.optJSONArray("customPackages") ?: JSONArray()
            val packages = (0 until pkgArr.length()).mapNotNull { i ->
                pkgArr.optString(i).takeIf { it.isNotBlank() }
            }

            val domainArr = obj.optJSONArray("blockedDomains") ?: JSONArray()
            val domains = (0 until domainArr.length()).mapNotNull { i ->
                domainArr.optString(i).takeIf { it.isNotBlank() }
            }

            saveChannels(channels)
            setMode(mode)
            saveCustomPackages(packages)
            saveBlockedDomains(domains)
            true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val PREFS_NAME = "tg_blocker_prefs"
        private const val KEY_ENABLED_LEGACY = "blocking_enabled"
        private const val KEY_MODE = "blocking_mode"
        private const val KEY_CHANNELS = "blocked_channels"
        private const val KEY_CUSTOM_PACKAGES = "custom_packages"
        private const val KEY_BLOCKED_DOMAINS = "blocked_domains"
    }
}
