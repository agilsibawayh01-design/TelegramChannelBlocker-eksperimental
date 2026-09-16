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
 * CATATAN PENTING soal cakupan: accessibility_service_config.xml TIDAK lagi
 * membatasi android:packageNames ke daftar tetap, supaya pengguna bisa
 * menambah aplikasi kustom secara dinamis tanpa perlu build ulang APK.
 * Konsekuensinya, sistem Android akan menampilkan deskripsi izin yang lebih
 * umum ("bisa mengakses semua aplikasi") saat mengaktifkan service ini di
 * Setelan — itu wajar, bukan tanda ada yang salah. Secara teknis event dari
 * SEMUA aplikasi akan sampai ke [onAccessibilityEvent], tapi baris pertama
 * di bawah langsung mengabaikan total (return tanpa memproses apa pun)
 * setiap package yang bukan [OFFICIAL_PACKAGE], bukan anggota
 * [CLONE_PACKAGES], dan bukan salah satu package kustom yang pengguna
 * daftarkan sendiri lewat repository — jadi cakupan efektifnya tetap sama
 * sekecil sebelumnya, hanya sumber daftarnya yang sekarang dinamis.
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
    }

    override fun onCreate() {
        super.onCreate()
        repository = BlockedChannelRepository(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return

        // Lapisan keamanan utama: apa pun isi accessibility_service_config.xml,
        // package selain OFFICIAL_PACKAGE, CLONE_PACKAGES, dan custom packages
        // milik pengguna sendiri diabaikan TOTAL di sini, tanpa membaca layar
        // sama sekali.
        val isOfficial = pkg == OFFICIAL_PACKAGE
        val isCloneOrCustom = !isOfficial && (pkg in CLONE_PACKAGES || pkg in repository.getCustomPackages())
        if (!isOfficial && !isCloneOrCustom) return

        if (repository.getMode() == BlockingMode.OFF) return

        // Aplikasi klon atau aplikasi kustom: langsung tendang ke Home,
        // tidak perlu baca layar sama sekali.
        if (isCloneOrCustom) {
            Log.d(TAG, "Aplikasi klon/kustom terdeteksi ($pkg), kembali ke Home")
            performGlobalAction(GLOBAL_ACTION_HOME)
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saat memproses event aksesibilitas", e)
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() {
        // Tidak ada state khusus yang perlu dibersihkan.
    }
}
