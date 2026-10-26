package com.mas.tgblocker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Cadangan ke lokasi yang dipilih pengguna lewat pemilih file bawaan Android
 * (Storage Access Framework). Kalau pengguna memilih folder di Google Drive,
 * file cadangan tersimpan di Drive-nya TANPA perlu login/OAuth/kunci API
 * di dalam aplikasi ini.
 *
 * Yang dicadangkan: isi [BlockedChannelRepository.exportData] (daftar channel,
 * aplikasi tambahan, domain, mode, dan pengaturan deteksi). PIN kunci aplikasi
 * TIDAK ikut dicadangkan.
 */
object BackupManager {

    private const val TAG = "TgBlockerBackup"
    private const val PREFS_NAME = "tg_blocker_backup"
    private const val KEY_URI = "target_uri"
    private const val KEY_AUTO = "auto_enabled"
    private const val KEY_LAST_OK = "last_success_at"
    private const val KEY_LAST_ERROR = "last_error"
    private const val WORK_PERIODIC = "tgblocker_backup_periodic"
    private const val WORK_ON_CHANGE = "tgblocker_backup_on_change"
    private const val MAX_READ_CHARS = 2_000_000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTarget(context: Context): Uri? =
        prefs(context).getString(KEY_URI, null)?.let { Uri.parse(it) }

    fun setTarget(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_URI, uri.toString()).apply()
    }

    fun isAutoEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, false)

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()
        if (enabled) schedulePeriodic(context) else cancelAll(context)
    }

    fun lastSuccessAt(context: Context): Long = prefs(context).getLong(KEY_LAST_OK, 0L)

    fun lastError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)

    /** Menulis cadangan sekarang. Harus dipanggil dari thread latar belakang. */
    fun backupNow(context: Context): Boolean {
        val uri = getTarget(context) ?: return false
        return try {
            val json = BlockedChannelRepository(context).exportData()
            writeText(context, uri, json)
            prefs(context).edit()
                .putLong(KEY_LAST_OK, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR)
                .apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Backup gagal", e)
            prefs(context).edit().putString(KEY_LAST_ERROR, e.message ?: e.javaClass.simpleName).apply()
            false
        }
    }

    fun writeText(context: Context, uri: Uri, text: String) {
        val resolver = context.contentResolver
        val previousSize = querySize(context, uri)

        var truncated = true
        var stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            null
        }
        if (stream == null) {
            truncated = false
            stream = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Tidak bisa membuka lokasi cadangan")
        }
        stream.use { out ->
            out.write(BackupPayload.build(text, previousSize, truncated))
            out.flush()
        }
    }

    fun readText(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Tidak bisa membuka file cadangan")
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            val sb = StringBuilder()
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
                if (sb.length > MAX_READ_CHARS) throw IOException("File terlalu besar untuk berkas cadangan")
            }
            return sb.toString()
        }
    }

    private fun querySize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
                } else {
                    -1L
                }
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /** Nama file + penyedia (mis. "Google Drive") untuk ditampilkan. */
    fun describeTarget(context: Context): String? {
        val uri = getTarget(context) ?: return null
        var name: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
        } catch (e: Exception) {
            // abaikan; tampilkan tanpa nama
        }
        val authority = uri.authority.orEmpty()
        val provider = when {
            authority.contains("docs", ignoreCase = true) -> "Google Drive"
            authority.contains("externalstorage", ignoreCase = true) -> "Penyimpanan perangkat"
            else -> authority
        }
        val fileName = name ?: "file cadangan"
        return "$fileName ($provider)"
    }

    fun formatTime(millis: Long): String =
        SimpleDateFormat("d MMM yyyy, HH.mm", Locale("id", "ID")).format(Date(millis))

    // ----- Penjadwalan (WorkManager) -----

    /**
     * Dipanggil tiap data berubah. Beberapa perubahan beruntun digabung jadi
     * satu cadangan, 15 detik setelah perubahan terakhir.
     */
    fun onDataChanged(context: Context) {
        if (!isAutoEnabled(context) || getTarget(context) == null) return
        try {
            val request = OneTimeWorkRequest.Builder(BackupWorker::class.java)
                .setInitialDelay(15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_ON_CHANGE, ExistingWorkPolicy.REPLACE, request)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menjadwalkan cadangan setelah perubahan", e)
        }
    }

    fun schedulePeriodic(context: Context) {
        try {
            val request = PeriodicWorkRequest.Builder(BackupWorker::class.java, 12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menjadwalkan cadangan berkala", e)
        }
    }

    fun cancelAll(context: Context) {
        try {
            val manager = WorkManager.getInstance(context.applicationContext)
            manager.cancelUniqueWork(WORK_PERIODIC)
            manager.cancelUniqueWork(WORK_ON_CHANGE)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal membatalkan jadwal cadangan", e)
        }
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        if (!BackupManager.isAutoEnabled(context)) return Result.success()
        val ok = BackupManager.backupNow(context)
        return when {
            ok -> Result.success()
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
        }
    }
}
