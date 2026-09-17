package com.mas.tgblocker

/**
 * Satu entri log Content Detection. SENGAJA cuma menyimpan metadata —
 * timestamp, package, dan jenis deteksi — TIDAK PERNAH menyimpan screenshot,
 * URL lengkap, atau isi teks yang terdeteksi.
 */
data class DetectionLogEntry(
    val timestamp: Long,
    val pkg: String,
    val detectionType: String
)
