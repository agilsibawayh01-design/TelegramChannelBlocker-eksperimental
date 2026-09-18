package com.mas.tgblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

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
 *
 * UPGRADE Image Content Detector (Fase 2b/2c, additive, DEFAULT OFF): kalau
 * toggle "Deteksi Gambar (AI)" diaktifkan pengguna dari MainActivity, dan
 * perangkat Android 11+ (API 30+, syarat takeScreenshot()), service
 * menjalankan LOOP SAMPLING BERKALA (Handler, interval
 * [IMAGE_DETECTION_INTERVAL_MS]) yang independen dari accessibility event —
 * bukan lagi dipicu oleh event, karena video yang berganti frame TIDAK SELALU
 * menghasilkan accessibility event yang relevan. Loop ini cuma benar-benar
 * mengambil screenshot kalau app di foreground saat ini ([currentForegroundPackage])
 * adalah Chrome/Telegram DAN toggle aktif DAN model tersedia DAN tidak sedang
 * cooldown. Screenshot → [ImageContentDetector] (100% on-device) → skor →
 * BACK kalau melewati threshold, lalu cooldown supaya tidak BACK berulang.
 * Detector TIDAK PERNAH aktif kalau modelnya belum dipasang pengguna sendiri
 * di assets — lihat TfliteImageContentDetector.kt untuk detail & keterbatasan.
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
    private var imageContentDetector: ImageContentDetector? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Package yang lagi di foreground, diupdate dari SETIAP accessibility
    // event yang masuk (lihat handleAccessibilityEvent). Dipakai loop
    // sampling di bawah supaya screenshot cuma diambil kalau Chrome/Telegram
    // memang lagi aktif — tapi PENGAMBILANNYA sendiri jalan lewat timer,
    // BUKAN menunggu event baru (video bisa ganti frame tanpa event).
    @Volatile private var currentForegroundPackage: String? = null

    // Guard supaya tidak ada 2 screenshot/inference yang jalan bertumpuk.
    @Volatile private var imageCheckInFlight = false

    // Kapan check terakhir mulai - dipakai watchdog di bawah untuk deteksi
    // kalau imageCheckInFlight "macet" true selamanya (mis. callback dari
    // detector/takeScreenshot ternyata tidak pernah terpanggil karena bug
    // lain), supaya pipeline bisa pulih sendiri alih-alih diam permanen.
    @Volatile private var imageCheckStartedAtMs = 0L

    // Kapan boleh screenshot lagi (dipakai baik untuk throttle normal
    // maupun cooldown ekstra setelah BACK supaya tidak BACK berulang-ulang).
    @Volatile private var nextImageCheckAllowedAtMs = 0L

    private val imageSamplingLoop = object : Runnable {
        override fun run() {
            try {
                runImageSamplingTick()
            } catch (e: Exception) {
                Log.e(TAG, "Error di image sampling loop", e)
            } finally {
                mainHandler.postDelayed(this, IMAGE_DETECTION_INTERVAL_MS)
            }
        }
    }

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

        // Interval loop sampling screenshot (BUKAN per-frame/per-event) —
        // sesuai requirement performa, target realistis ~1 detik.
        private const val IMAGE_DETECTION_INTERVAL_MS = 1000L

        // Cooldown TAMBAHAN setelah BACK dipicu oleh deteksi gambar, supaya
        // tidak BACK berulang-ulang selama konten yang sama masih di layar.
        private const val IMAGE_BACK_COOLDOWN_MS = 3000L

        // Kalau imageCheckInFlight macet true lebih lama dari ini, dianggap
        // callback-nya hilang (bug lain) - direset paksa supaya pipeline
        // tidak diam permanen. Longgar (10 detik) karena inference on-device
        // biasanya cuma perlu ratusan ms, jadi ini murni jaring pengaman.
        private const val IMAGE_INFLIGHT_TIMEOUT_MS = 10000L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        mainHandler.removeCallbacks(imageSamplingLoop)
        mainHandler.postDelayed(imageSamplingLoop, IMAGE_DETECTION_INTERVAL_MS)
        Log.d(TAG, "Image sampling loop dimulai (service connected)")
    }

    override fun onCreate() {
        super.onCreate()
        repository = BlockedChannelRepository(this)
        imageContentDetector = ImageContentDetectorFactory.get(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(imageSamplingLoop)
        imageContentDetector?.close()
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

        // Dipakai loop sampling gambar (lihat runImageSamplingTick) supaya
        // screenshot cuma diambil kalau app ini memang sedang di foreground.
        // Diupdate untuk SEMUA package (bukan cuma yang relevan ke fitur
        // blokir lain), supaya kalau user pindah ke app lain, loop tahu dan
        // otomatis berhenti sampling.
        currentForegroundPackage = pkg

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
                        return
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
                    return
                }
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * Dipanggil oleh [imageSamplingLoop] tiap [IMAGE_DETECTION_INTERVAL_MS],
     * BUKAN oleh accessibility event — supaya video yang berganti frame
     * tanpa accessibility event baru tetap ke-sample secara berkala.
     * Semua syarat (toggle, versi Android, model, foreground package,
     * cooldown, tidak ada inference lain yang jalan) dicek di sini; kalau
     * satu saja gagal, fungsi langsung return tanpa efek samping.
     */
    private fun runImageSamplingTick() {
        if (!repository.isImageDetectionEnabled()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return // takeScreenshot() butuh Android 11+

        val detector = imageContentDetector ?: return
        if (!detector.isAvailable()) return // model belum dipasang pengguna

        val pkg = currentForegroundPackage ?: return
        val isTarget = pkg == CHROME_PACKAGE || pkg == OFFICIAL_PACKAGE
        if (!isTarget) return

        if (repository.getMode() == BlockingMode.OFF) return

        val now = System.currentTimeMillis()
        if (now < nextImageCheckAllowedAtMs) return // masih cooldown (normal atau habis BACK)

        if (imageCheckInFlight) {
            val stuckMs = now - imageCheckStartedAtMs
            if (stuckMs < IMAGE_INFLIGHT_TIMEOUT_MS) {
                // Ada screenshot/inference sebelumnya yang belum selesai -
                // JANGAN numpuk, tunggu tick berikutnya saja.
                return
            }
            // Sudah macet kelamaan (callback sebelumnya diduga tidak pernah
            // terpanggil) - reset paksa supaya pipeline pulih, dan catat di
            // log biar keliatan di UI kalau ini yang jadi masalah.
            Log.w(TAG, "DBG_INFLIGHT_TIMEOUT pkg=$pkg stuckMs=$stuckMs - reset paksa")
            repository.addDetectionLogEntry(pkg, "DBG_INFLIGHT_TIMEOUT")
            imageCheckInFlight = false
        }
        imageCheckInFlight = true
        imageCheckStartedAtMs = now
        nextImageCheckAllowedAtMs = now + IMAGE_DETECTION_INTERVAL_MS

        Log.d(TAG, "SCREENSHOT_REQUESTED pkg=$pkg")
        repository.addDetectionLogEntry(pkg, "DBG_SCREENSHOT_REQUESTED")
        captureScreenshotAndClassify(pkg, detector)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshotAndClassify(pkg: String, detector: ImageContentDetector) {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            Log.d(TAG, "SCREENSHOT_SUCCESS pkg=$pkg")
                            repository.addDetectionLogEntry(pkg, "DBG_SCREENSHOT_SUCCESS")
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val bitmap = hardwareBufferToBitmap(hardwareBuffer)
                            hardwareBuffer.close()
                            if (bitmap == null) {
                                Log.d(TAG, "SCREENSHOT_FAILED pkg=$pkg reason=bitmap_null_after_conversion")
                                repository.addDetectionLogEntry(pkg, "DBG_BITMAP_NULL")
                                imageCheckInFlight = false
                                return
                            }

                            Log.d(TAG, "INFERENCE_STARTED pkg=$pkg")
                            repository.addDetectionLogEntry(pkg, "DBG_INFERENCE_STARTED")
                            detector.detect(bitmap) { score ->
                                imageCheckInFlight = false

                                if (score == ImageContentDetector.SCORE_UNAVAILABLE) {
                                    Log.d(TAG, "INFERENCE_RESULT pkg=$pkg score=UNAVAILABLE")
                                    repository.addDetectionLogEntry(pkg, "DBG_SCORE_UNAVAILABLE")
                                    return@detect
                                }

                                Log.d(TAG, "INFERENCE_RESULT pkg=$pkg score=$score")
                                repository.addDetectionLogEntry(pkg, "DBG_SCORE_$score")
                                val threshold = repository.getImageDetectionThreshold()
                                if (score >= threshold) {
                                    Log.d(TAG, "BLOCK_TRIGGERED pkg=$pkg score=$score threshold=$threshold")
                                    repository.addDetectionLogEntry(pkg, "IMAGE")
                                    performGlobalAction(GLOBAL_ACTION_BACK)
                                    Log.d(TAG, "BACK_EXECUTED pkg=$pkg")
                                    // Cooldown ekstra supaya tidak BACK berulang-ulang
                                    // selama konten yang sama masih di layar.
                                    nextImageCheckAllowedAtMs = System.currentTimeMillis() + IMAGE_BACK_COOLDOWN_MS
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error memproses hasil screenshot", e)
                            repository.addDetectionLogEntry(pkg, "DBG_EXCEPTION_${e.javaClass.simpleName}")
                            imageCheckInFlight = false
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        // PENTING: screenshot gagal TIDAK dianggap "aman" - cuma
                        // dilewati & dicatat, coba lagi di tick berikutnya.
                        Log.d(TAG, "SCREENSHOT_FAILED pkg=$pkg errorCode=$errorCode")
                        repository.addDetectionLogEntry(pkg, "DBG_SCREENSHOT_FAILED_$errorCode")
                        imageCheckInFlight = false
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memanggil takeScreenshot()", e)
            imageCheckInFlight = false
        }
    }

    /** Konversi hasil takeScreenshot() (HardwareBuffer) ke Bitmap software biasa. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun hardwareBufferToBitmap(hardwareBuffer: HardwareBuffer): Bitmap? {
        return try {
            val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null) ?: return null
            // Interpreter TFLite butuh bitmap software (ARGB_8888) biasa, bukan
            // hardware bitmap, supaya bisa dibaca getPixels().
            hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal konversi screenshot ke Bitmap", e)
            null
        }
    }

    override fun onInterrupt() {
        // Tidak ada state khusus yang perlu dibersihkan.
    }
}
