package com.mas.tgblocker

/**
 * Penjaga Mode Ketat: mengenali apakah layar Pengaturan Android yang sedang
 * terbuka adalah halaman yang bisa dipakai untuk mematikan/menghapus aplikasi
 * ini. Kalau ya, service menekan BACK.
 *
 * Yang dijaga (hanya saat Mode Ketat, dan hanya kalau nama aplikasi ini
 * tampil di layar):
 *  1. Halaman detail layanan Aksesibilitas aplikasi ini (tempat saklar on/off).
 *  2. Halaman Info Aplikasi aplikasi ini (tombol Copot/Uninstall, Paksa berhenti, Nonaktifkan).
 *  3. Halaman Admin Perangkat (tempat mencabut perlindungan uninstall).
 *
 * Yang SENGAJA dibiarkan: daftar Aksesibilitas, daftar Aplikasi, dan seluruh
 * Pengaturan lain tetap bisa dibuka seperti biasa — layanan lain tetap bisa
 * diatur. Penentu halaman adalah penanda khusus (teks deskripsi layanan,
 * tombol Copot, dsb.) yang hanya muncul bersama nama aplikasi ini.
 *
 * Murni logika teks, tanpa akses ke Android, supaya mudah diperiksa.
 */
object StrictSettingsGuard {

    /** Package Pengaturan yang dipantau. Samsung One UI tetap memakai com.android.settings. */
    val SETTINGS_PACKAGES = setOf("com.android.settings")

    // Teks tombol pada halaman Info Aplikasi (dibandingkan persis, huruf kecil).
    private val APP_INFO_MARKERS = setOf(
        "uninstall", "copot", "copot pemasangan", "hapus instalan", "hapus instalasi",
        "force stop", "paksa berhenti", "disable", "nonaktifkan", "matikan"
    )

    // Potongan teks pada halaman Admin Perangkat (dicocokkan dengan "mengandung").
    private val DEVICE_ADMIN_MARKERS = listOf(
        "admin perangkat", "aplikasi admin", "device admin", "device administrator"
    )

    /**
     * @param screenTexts semua teks di layar (boleh huruf besar/kecil apa saja)
     * @param appLabel nama aplikasi ini, mis. "Telegram Channel Blocker"
     * @param serviceDescription deskripsi layanan aksesibilitas aplikasi ini
     */
    fun isProtectedPage(
        screenTexts: List<String>,
        appLabel: String,
        serviceDescription: String
    ): Boolean {
        val label = appLabel.trim().lowercase()
        if (label.isEmpty()) return false

        val lower = screenTexts.map { it.lowercase() }
        if (lower.none { it.contains(label) }) return false

        // 1. Halaman detail layanan Aksesibilitas: menampilkan deskripsi layanan,
        //    atau saklar "Gunakan <nama>" / "Use <nama>".
        val descStart = serviceDescription.trim().lowercase().take(30)
        val onAccessibilityDetail = lower.any {
            (descStart.length >= 10 && it.contains(descStart)) ||
                it == "gunakan $label" || it == "use $label"
        }
        if (onAccessibilityDetail) return true

        // 2. Halaman Info Aplikasi: nama aplikasi + tombol Copot/Paksa berhenti.
        if (lower.any { it in APP_INFO_MARKERS }) return true

        // 3. Halaman Admin Perangkat yang memuat nama aplikasi ini.
        if (lower.any { text -> DEVICE_ADMIN_MARKERS.any { text.contains(it) } }) return true

        return false
    }
}
