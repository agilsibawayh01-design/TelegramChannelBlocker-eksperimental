package com.mas.tgblocker

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Service yang membaca tampilan aplikasi Telegram (dan varian sejenisnya, lihat
 * accessibility_service_config.xml) untuk mendeteksi channel yang ada di daftar
 * blokir, lalu menjalankan aksi BACK agar halaman channel tersebut tidak bisa
 * dibuka/dilanjutkan.
 *
 * Ditambah lapisan baru: kalau aplikasi yang terbuka adalah salah satu KLON
 * Telegram (Telegram X, Nekogram, Plus Messenger, dst — lihat CLONE_PACKAGES)
 * ATAU salah satu aplikasi kustom yang didaftarkan pengguna sendiri lewat
 * fitur "Aplikasi/Klon Tambahan" di MainActivity, service langsung menekan
 * tombol Home, apa pun mode blokirnya (selama bukan OFF), apa pun isi
 * layarnya — tidak ada deteksi channel per-aplikasi untuk keduanya. Hanya
 * [OFFICIAL_PACKAGE] yang diproses lewat logic deteksi channel biasa di bawah.
 *
 * UPGRADE Browser + Website blocking (tidak mengubah logic Telegram di atas):
 * - [CHROME_PACKAGE] adalah satu-satunya browser yang DIIZINKAN. Saat Chrome
 *   aktif, service membaca address bar (atau fallback ke teks layar) dan
 *   membandingkannya dengan domain custom pengguna + [AdultDomainList]
 *   bawaan lewat [WebsiteDetector] — kalau cocok, BACK.
 * - [BROWSER_BLOCK_PACKAGES] berisi browser lain yang DIKENAL (Firefox,
 *   Samsung Internet, Edge, dst) — diperlakukan sama seperti CLONE_PACKAGES,
 *   langsung ditendang ke Home. Browser yang tidak ada di daftar ini masih
 *   bisa diblokir lewat "Aplikasi/Klon Tambahan" (custom packages), sama
 *   seperti aplikasi lain.
 *
 * UPGRADE Content Detection (Fase 2, additive, tidak mengubah logic di
 * atas): kalau toggle "Content Detection" aktif (default: aktif), teks yang
 * SUDAH terbaca lewat Accessibility API di Chrome & Telegram resmi juga
 * dicek lewat [VulgarTextDetector] (keyword + confidence threshold). Kalau
 * confidence-nya cukup tinggi, BACK — dicatat ke log lokal (metadata saja,
 * lihat [DetectionLogEntry], TIDAK PERNAH menyimpan screenshot/isi teks).
 * Deteksi gambar NSFW BELUM aktif — lihat [NsfwImageClassifier] untuk
 * penjelasan lengkap kenapa & cara mengaktifkannya nanti.
 *
 * CATATAN PENTING soal cakupan: accessibility_service_config.xml TIDAK lagi
 * membatasi android:packageNames ke daftar tetap, supaya pengguna bisa
 * menambah aplikasi kustom secara dinamis tanpa perlu build ulang APK.
 * Konsekuensinya, sistem Android akan menampilkan deskripsi izin yang lebih
 * umum ("bisa mengakses semua aplikasi") saat mengaktifkan service ini di
 * Setelan — itu wajar, bukan tanda ada yang salah. Secara teknis event dari
 * SEMUA aplikasi akan sampai ke [onAccessibilityEvent], tapi baris pertama
 * di bawah langsung mengabaikan total (return tanpa memproses apa pun)
 * setiap package yang bukan [OFFICIAL_PACKAGE], bukan [CHROME_PACKAGE],
 * bukan anggota [CLONE_PACKAGES]/[BROWSER_BLOCK_PACKAGES], dan bukan salah
 * satu package kustom yang pengguna daftarkan sendiri lewat repository —
 * jadi cakupan efektifnya tetap sekecil sebelumnya (plus Chrome & browser
 * yang memang sengaja ditambahkan di upgrade ini), hanya sumber daftarnya
 * yang sekarang dinamis.
 */
class TelegramBlockAccessibilityService : AccessibilityService() {

    private lateinit var repository: BlockedChannelRepository

    companion object {
        private const val TAG = "TgChannelBlocker"
        private const val OFFICIAL_PACKAGE = "org.telegram.messenger"

        // Klon/fork Telegram yang langsung ditendang ke Home begitu dibuka.
        private val CLONE_PACKAGES = setOf(
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.telegram.plus",
            "nekox.messenger",
            "tw.nekomimi.nekogram",
            "org.thunderdog.challegram",   // Telegram X
            "org.forkclient.messenger",
            "com.exteragram.messenger",
            "it.owlgram.android",
            "ua.itaysonlab.messenger",
            "top.qwq2333.nullgram",
            "com.cool2645.nekolite",
            "me.ninjagram.messenger",
            "org.ninjagram.messenger",
            "org.telegram.mdgram",
            "org.telegram.mdgramyou",
            "org.telegram.BifToGram",
            "ellipi.messenger",
            "belloworld.mercurygram"
        )

        // Satu-satunya browser yang diizinkan.
        private const val CHROME_PACKAGE = "com.android.chrome"

        // Browser lain yang DIKENAL — langsung ditendang ke Home seperti klon
        // Telegram. Non-exhaustive by design (lihat komentar kelas di atas);
        // browser di luar daftar ini bisa ditambahkan lewat Custom Package.
        private val BROWSER_BLOCK_PACKAGES = setOf(
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.focus",
            "org.mozilla.klar",
            "com.sec.android.app.sbrowser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.opera.browser.beta",
            "com.opera.mini.native",
            "com.opera.gx",
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.duckduckgo.mobile.android",
            "com.kiwibrowser.browser",
            "com.vivaldi.browser",
            "com.yandex.browser",
            "com.UCMobile.intl",
            "com.ucweb.browser",
            "mark.via.gp",
            "com.mmbox.xbrowser",
            "com.android.browser"
        )
    }

    override fun onCreate() {
        super.onCreate()
        repository = BlockedChannelRepository(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // PENTING: SELURUH isi fungsi ini dibungkus try/catch. Accessibility
        // service jalan di proses utama aplikasi — kalau ada exception yang
        // lolos sampai ke luar fungsi ini, Android akan meng-crash lalu
        // otomatis me-restart service-nya berkali-kali ("app keeps
        // stopping"/"terus berhenti" loop), bahkan waktu HP cuma didiamkan
        // di homescreen. Menangkap semua Exception di sini membuat bug apa
        // pun (di service ini maupun di detector-detector yang dipanggilnya)
        // paling parah cuma "gagal mendeteksi sekali", bukan crash total.
        try {
            handleAccessibilityEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled error di onAccessibilityEvent, event diabaikan", e)
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return

        // Lapisan keamanan utama: apa pun isi accessibility_service_config.xml,
        // package selain OFFICIAL_PACKAGE, CHROME_PACKAGE, CLONE_PACKAGES,
        // BROWSER_BLOCK_PACKAGES, dan custom packages milik pengguna sendiri
        // diabaikan TOTAL di sini, tanpa membaca layar sama sekali.
        val isOfficial = pkg == OFFICIAL_PACKAGE
        val isChrome = pkg == CHROME_PACKAGE
        val isCloneOrCustom = !isOfficial && !isChrome &&
            (pkg in CLONE_PACKAGES || pkg in BROWSER_BLOCK_PACKAGES || pkg in repository.getCustomPackages())
        if (!isOfficial && !isChrome && !isCloneOrCustom) return

        if (repository.getMode() == BlockingMode.OFF) return

        // Aplikasi klon, browser terlarang, atau aplikasi kustom: langsung
        // tendang ke Home, tidak perlu baca layar sama sekali.
        if (isCloneOrCustom) {
            Log.d(TAG, "Aplikasi klon/browser terlarang/kustom terdeteksi ($pkg), kembali ke Home")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // Chrome: cek address bar terhadap domain blokir (custom + bawaan),
        // DITAMBAH cek Content Detection (teks vulgar di halaman) kalau aktif.
        if (isChrome) {
            val root = rootInActiveWindow ?: return
            try {
                val blockedDomains = repository.getBlockedDomains() + AdultDomainList.DOMAINS
                val urlBarText = WebsiteDetector.findUrlBarText(root)
                val allTexts = WebsiteDetector.collectAllTexts(root)
                val candidateText = urlBarText ?: allTexts.joinToString(" ")

                val matchedDomain = WebsiteDetector.findBlockedDomain(candidateText, blockedDomains)
                if (matchedDomain != null) {
                    Log.d(TAG, "Domain diblokir terdeteksi ($matchedDomain), menjalankan BACK")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return
                }

                if (repository.isContentDetectionEnabled()) {
                    val textResult = VulgarTextDetector.analyze(allTexts)
                    if (textResult.isBlocked) {
                        Log.d(TAG, "Konten vulgar terdeteksi di Chrome (confidence=${textResult.confidence}), menjalankan BACK")
                        repository.addDetectionLogEntry(pkg, "TEXT")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
            } finally {
                root.recycle()
            }
            return
        }

        // Dari sini seterusnya khusus OFFICIAL_PACKAGE saja.
        val root = rootInActiveWindow ?: return

        try {
            val screenTexts = ChannelDetector.collectTexts(root)
            if (screenTexts.normalized.isEmpty()) return

            val blockedList = repository.getChannels()

            val matchedUsername = ChannelDetector.containsBlockedUsername(screenTexts.normalized, blockedList)
            val matchedName = ChannelDetector.containsBlockedChannelName(screenTexts.raw, blockedList)
            val matchedArgo = blockedList.any { it.type == ChannelType.USERNAME && it.normalized() == "argo" } &&
                ArgoSearchDetector.isArgoSearchPresent(screenTexts.normalized)

            if (matchedUsername != null || matchedName != null || matchedArgo) {
                Log.d(TAG, "Channel diblokir terdeteksi, menjalankan BACK")
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }

            if (repository.isContentDetectionEnabled()) {
                val textResult = VulgarTextDetector.analyze(screenTexts.raw)
                if (textResult.isBlocked) {
                    Log.d(TAG, "Konten vulgar terdeteksi di Telegram (confidence=${textResult.confidence}), menjalankan BACK")
                    repository.addDetectionLogEntry(pkg, "TEXT")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() {
        // Tidak ada state khusus yang perlu dibersihkan.
    }
}
