package com.mas.tgblocker

/**
 * Daftar BAWAAN domain dewasa yang otomatis diblokir tanpa perlu
 * ditambahkan manual oleh pengguna.
 *
 * PENTING — batasan yang jujur:
 * - Ini daftar yang sudah diperluas (~50 domain), TAPI TETAP BUKAN
 *   daftar lengkap/komprehensif. Internet punya puluhan ribu domain
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
        // Tube / streaming umum
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
        "youporn.com", "spankbang.com", "tnaflix.com", "tube8.com", "eporner.com",
        "txxx.com", "drtuber.com", "sunporno.com", "porn.com", "beeg.com",
        "xxxbunker.com", "pornone.com", "porntrex.com", "hqporner.com",
        "thumbzilla.com", "vporn.com", "keezmovies.com", "spankwire.com",
        "pornhd.com", "hclips.com",

        // Cam / live
        "chaturbate.com", "stripchat.com", "livejasmin.com", "bongacams.com",
        "myfreecams.com", "cam4.com", "camsoda.com", "flirt4free.com",
        "streamate.com", "camwhores.tv",

        // Kreator / subscription
        "onlyfans.com", "fansly.com",

        // Hentai / anime dewasa
        "rule34.xxx", "nhentai.net", "e-hentai.org", "hanime.tv",
        "hentaihaven.xxx",

        // Lain-lain yang cukup dikenal
        "motherless.com", "fapello.com", "erome.com", "imagefap.com",
        "brazzers.com", "javhd.com", "missav.com"
    )
}
