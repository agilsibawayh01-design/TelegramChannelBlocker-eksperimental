package com.mas.tgblocker

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hashing PIN. Sengaja TANPA dependensi Android supaya logikanya bisa diuji
 * murni di JVM. PIN tidak pernah disimpan polos: yang disimpan hanya hasil
 * PBKDF2 (HMAC-SHA256) dengan salt acak per-PIN.
 */
object PinHasher {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8
    private const val ITERATIONS = 60_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun isValidPin(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(): ByteArray {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Perbandingan waktu-konstan supaya tidak bocor lewat selisih waktu. */
    fun matches(pin: String, salt: ByteArray, expected: ByteArray, iterations: Int = ITERATIONS): Boolean =
        MessageDigest.isEqual(hash(pin, salt, iterations), expected)

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}

/**
 * Penundaan setelah salah PIN beruntun: tiap kelipatan 5 kali salah, aplikasi
 * menolak percobaan selama 30 detik, lalu 60, 120, ... maksimal 10 menit.
 * Murni logika, tanpa Android.
 */
object LockoutPolicy {
    const val ATTEMPTS_PER_STAGE = 5
    private const val BASE_SECONDS = 30L
    private const val MAX_SECONDS = 600L

    fun delayMsForFailures(failures: Int): Long {
        if (failures < ATTEMPTS_PER_STAGE || failures % ATTEMPTS_PER_STAGE != 0) return 0L
        val stage = failures / ATTEMPTS_PER_STAGE
        val shift = (stage - 1).coerceAtMost(10)
        val seconds = (BASE_SECONDS shl shift).coerceAtMost(MAX_SECONDS)
        return seconds * 1000L
    }
}

/**
 * Kunci aplikasi (PIN + sidik jari opsional) untuk layar utama aplikasi ini.
 *
 * Data kunci disimpan di SharedPreferences TERPISAH ("tg_blocker_lock") dan
 * TIDAK ikut diekspor/dicadangkan/dipulihkan, supaya file cadangan tidak
 * membawa hash PIN dan pemulihan tidak bisa dipakai untuk melepas kunci.
 */
class AppLock(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT)

    fun pinLength(): Int = prefs.getInt(KEY_LENGTH, PinHasher.MAX_LENGTH)

    fun setPin(pin: String) {
        require(PinHasher.isValidPin(pin)) { "PIN harus 4-8 digit angka" }
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, PinHasher.encode(salt))
            .putString(KEY_HASH, PinHasher.encode(hash))
            .putInt(KEY_LENGTH, pin.length)
            .putInt(KEY_FAILS, 0)
            .putLong(KEY_LOCKED_UNTIL, 0L)
            .apply()
    }

    /** Menghapus PIN beserta semua pengaturan kunci, lalu menganggap sesi terbuka. */
    fun clear() {
        prefs.edit().clear().apply()
        markUnlocked()
    }

    fun lockoutRemainingMs(): Long {
        val until = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Mengembalikan true kalau PIN benar. Selama masa tunggu, selalu false. */
    fun verify(pin: String): Boolean {
        if (lockoutRemainingMs() > 0L) return false
        val saltText = prefs.getString(KEY_SALT, null) ?: return false
        val hashText = prefs.getString(KEY_HASH, null) ?: return false
        val ok = try {
            PinHasher.matches(pin, PinHasher.decode(saltText), PinHasher.decode(hashText))
        } catch (e: Exception) {
            false
        }
        if (ok) {
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCKED_UNTIL, 0L).apply()
        } else {
            val fails = prefs.getInt(KEY_FAILS, 0) + 1
            val delay = LockoutPolicy.delayMsForFailures(fails)
            prefs.edit()
                .putInt(KEY_FAILS, fails)
                .putLong(KEY_LOCKED_UNTIL, if (delay > 0L) System.currentTimeMillis() + delay else 0L)
                .apply()
        }
        return ok
    }

    // ----- Pengaturan -----

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC, false)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
    }

    /** Detik di latar belakang sebelum terkunci lagi. 0 = langsung. */
    fun autoLockSeconds(): Int = prefs.getInt(KEY_AUTO_LOCK, DEFAULT_AUTO_LOCK_SECONDS)

    fun setAutoLockSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_AUTO_LOCK, seconds.coerceAtLeast(0)).apply()
    }

    // ----- Status sesi (hanya di memori; hilang kalau proses aplikasi mati) -----

    fun markUnlocked() {
        unlocked = true
        leftAtElapsed = 0L
        internalLaunch = false
    }

    /** Dipanggil saat layar aplikasi tidak lagi tampil (onStop). */
    fun markLeft() {
        leftAtElapsed = SystemClock.elapsedRealtime()
    }

    /**
     * Dipanggil sebelum aplikasi sendiri membuka layar lain yang pasti kembali
     * (Pengaturan Aksesibilitas, pemilih file, dst.), supaya pengguna tidak
     * terkunci di tengah jalan. Masa tenggangnya dibatasi [INTERNAL_GRACE_MS].
     */
    fun markInternalLaunch() {
        internalLaunch = true
    }

    /** Apakah layar kunci harus ditampilkan sekarang. */
    fun needsUnlock(): Boolean {
        if (!isEnabled()) return false
        if (!unlocked) return true
        if (leftAtElapsed == 0L) return false

        val autoMs = autoLockSeconds() * 1000L
        val limit = if (internalLaunch) maxOf(autoMs, INTERNAL_GRACE_MS) else autoMs
        internalLaunch = false
        if (SystemClock.elapsedRealtime() - leftAtElapsed >= limit) {
            unlocked = false
            return true
        }
        return false
    }

    companion object {
        private const val PREFS_NAME = "tg_blocker_lock"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_LENGTH = "pin_length"
        private const val KEY_FAILS = "failed_attempts"
        private const val KEY_LOCKED_UNTIL = "locked_until"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_AUTO_LOCK = "auto_lock_seconds"

        const val DEFAULT_AUTO_LOCK_SECONDS = 30
        private const val INTERNAL_GRACE_MS = 5 * 60 * 1000L

        @Volatile private var unlocked = false
        @Volatile private var leftAtElapsed = 0L
        @Volatile private var internalLaunch = false
    }
}
