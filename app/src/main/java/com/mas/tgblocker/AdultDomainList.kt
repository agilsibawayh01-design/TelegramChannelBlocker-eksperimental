package com.mas.tgblocker

/**
 * Daftar BAWAAN domain dewasa yang otomatis diblokir tanpa perlu
 * ditambahkan manual oleh pengguna.
 *
 * PENTING — batasan yang jujur:
 * - Ini SEED LIST kecil berisi beberapa domain dewasa paling umum dikenal,
 *   BUKAN daftar lengkap/komprehensif. Internet punya puluhan ribu domain
 *   dewasa, dan domain baru terus bermunculan (termasuk domain kloning yang
 *   sengaja dibuat untuk menghindari blokir).
 * - Text-matching lewat Accessibility API (baca address bar Chrome) TIDAK
 *   sekuat filtering di level DNS/jaringan, dan bisa dilewati lewat mode
 *   Incognito yang menyembunyikan address bar, aplikasi lain, atau domain
 *   yang belum ada di daftar ini.
 * - Untuk proteksi yang jauh lebih kuat dan luas cakupannya, pertimbangkan
 *   menambah lapisan filtering DNS di level perangkat (mis. Private DNS ke
 *   penyedia yang punya kategori "family filter", seperti
 *   family.cloudflare-dns.com atau security-filter-dns.cleanbrowsing.org)
 *   — itu bekerja di semua aplikasi/browser sekaligus, bukan cuma Chrome.
 *
 * Pengguna tetap bisa menambah domain lain secara manual lewat menu
 * "Website yang Diblokir".
 */
object AdultDomainList {
    val DOMAINS: Set<String> = setOf(
        "pornhub.com",
        "xvideos.com",
        "xnxx.com",
        "xhamster.com",
        "redtube.com",
        "youporn.com",
        "spankbang.com",
        "chaturbate.com",
        "onlyfans.com",
        "stripchat.com",
        "brazzers.com",
        "livejasmin.com",
        "motherless.com",
        "rule34.xxx",
        "fapello.com"
    )
}
