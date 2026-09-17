package com.mas.tgblocker

import android.content.Context
import android.graphics.Bitmap

/**
 * Arsitektur untuk klasifikasi gambar NSFW on-device. STATUS SAAT INI:
 * BELUM AKTIF — tidak ada model terlatih yang di-bundle di project ini.
 *
 * KENAPA belum aktif (jujur, bukan alasan teknis dibuat-buat):
 * Klasifikasi visual NSFW butuh model machine learning yang sudah dilatih
 * dengan dataset besar (jutaan gambar). Model seperti itu tidak bisa dibuat
 * dari nol lewat coding biasa — perlu dataset + training + infrastruktur ML
 * yang di luar cakupan sesi ini. Daripada bikin kode yang PURA-PURA jalan
 * (misal selalu return "aman" atau assign angka acak), interface ini secara
 * eksplisit melaporkan dirinya "tidak tersedia" sampai model sungguhan
 * dipasang.
 *
 * CARA MENGAKTIFKAN NANTI (kalau kamu mau lanjutkan sendiri/minta bantuan):
 * 1. Sumber model TFLite NSFW open-source yang sudah ada, misal hasil
 *    konversi dari proyek seperti GantMan/nsfw_model (MIT license) ke
 *    format .tflite.
 * 2. Taruh file model di `app/src/main/assets/nsfw_model.tflite`.
 * 3. Tambah dependency TensorFlow Lite di app/build.gradle.kts:
 *    implementation("org.tensorflow:tensorflow-lite:2.14.0")
 * 4. Implementasikan [NsfwImageClassifier] baru yang memuat model itu lewat
 *    TFLite Interpreter, isi [classify] dengan preprocessing gambar +
 *    inference sungguhan, lalu ganti instance di [NsfwImageClassifierFactory]
 *    dari [UnavailableNsfwImageClassifier] ke implementasi barumu.
 * 5. Wiring pengambilan gambar (screenshot layar via
 *    AccessibilityService.takeScreenshot(), Android 11+) BELUM dipasang di
 *    TelegramBlockAccessibilityService pada fase ini — itu bagian terpisah
 *    (async, butuh testing di device asli) yang sengaja ditunda supaya tidak
 *    mengganggu fitur yang sudah stabil. Lihat catatan di README/ringkasan
 *    upgrade.
 */
interface NsfwImageClassifier {
    /** True kalau classifier ini punya model sungguhan dan siap dipakai. */
    fun isAvailable(): Boolean

    /**
     * Klasifikasi satu bitmap. HARUS hanya dipanggil kalau [isAvailable]
     * true. Implementasi wajib memproses bitmap sepenuhnya di perangkat
     * (tidak boleh upload ke server mana pun).
     */
    fun classify(bitmap: Bitmap): NsfwResult
}

data class NsfwResult(
    val confidence: Double, // 0.0 - 1.0
    val isBlocked: Boolean
)

/** Implementasi default: selalu melaporkan diri tidak tersedia, apa adanya. */
class UnavailableNsfwImageClassifier : NsfwImageClassifier {
    override fun isAvailable(): Boolean = false

    override fun classify(bitmap: Bitmap): NsfwResult {
        // Tidak seharusnya pernah dipanggil selama isAvailable() == false,
        // tapi tetap dijaga aman (fail-open, bukan fail-block) kalau
        // terpanggil karena kesalahan pemanggil.
        return NsfwResult(confidence = 0.0, isBlocked = false)
    }
}

object NsfwImageClassifierFactory {
    /**
     * Titik ganti tunggal untuk mengaktifkan classifier sungguhan nanti.
     * [context] disediakan untuk implementasi masa depan yang perlu buka
     * file model dari assets.
     */
    fun create(context: Context): NsfwImageClassifier = UnavailableNsfwImageClassifier()
}
