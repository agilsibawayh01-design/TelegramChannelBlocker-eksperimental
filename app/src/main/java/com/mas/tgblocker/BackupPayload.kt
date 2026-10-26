package com.mas.tgblocker

/**
 * Menyusun isi file cadangan sebelum ditulis. Tanpa dependensi Android supaya
 * bisa diuji murni.
 *
 * Latar belakang: sebagian penyedia dokumen (termasuk Google Drive pada
 * sebagian perangkat) tidak mendukung mode tulis yang memotong isi lama
 * ("wt"). Kalau terpaksa memakai mode "w" dan file lama LEBIH PANJANG dari
 * yang baru, sisa ekor file lama akan tertinggal dan merusak JSON. Solusinya:
 * isi sisa panjangnya dengan spasi. Parser JSON mengabaikan spasi di ujung,
 * jadi file tetap valid.
 */
object BackupPayload {

    fun build(text: String, previousSize: Long, truncated: Boolean): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (truncated || previousSize <= bytes.size) return bytes
        val padded = ByteArray(previousSize.toInt())
        System.arraycopy(bytes, 0, padded, 0, bytes.size)
        for (i in bytes.size until padded.size) padded[i] = ' '.code.toByte()
        return padded
    }
}
