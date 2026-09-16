package com.mas.tgblocker

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Receiver untuk Device Admin. Tujuannya semata-mata agar aplikasi tidak bisa
 * di-uninstall langsung dari Setelan tanpa menonaktifkan hak admin ini dulu
 * (langkah tambahan yang disengaja, sesuai kebutuhan kontrol diri pengguna).
 *
 * PENTING: File ini SENGAJA berdiri sendiri dan TIDAK menyentuh/memanggil
 * apa pun dari TelegramBlockAccessibilityService, ChannelDetector, atau
 * ArgoSearchDetector — supaya fitur pemblokiran channel yang sudah berjalan
 * tidak ikut terganggu oleh perubahan ini.
 */
class TgBlockerDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, R.string.device_admin_enabled, Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, R.string.device_admin_disabled, Toast.LENGTH_SHORT).show()
    }
}
