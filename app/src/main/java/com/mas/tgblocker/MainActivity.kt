package com.mas.tgblocker

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.mas.tgblocker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: BlockedChannelRepository
    private lateinit var appLock: AppLock
    private lateinit var adapter: ChannelAdapter
    private lateinit var packageAdapter: PackageAdapter
    private lateinit var domainAdapter: DomainAdapter
    private var currentMode: BlockingMode = BlockingMode.NORMAL

    // ----- Status kunci aplikasi -----
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pinBuffer = StringBuilder()
    private var lockVisible = false
    private var biometricShowing = false
    private var lockoutTicker: Runnable? = null
    private var pendingShareIntent: Intent? = null

    // ----- Pemilih file untuk cadangan (Google Drive muncul di pemilih ini) -----
    private val createBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) onBackupLocationChosen(uri)
        }

    private val openRestoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) restoreFromUri(uri)
        }

    companion object {
        private const val PENALTY_STEPS = 10
        private const val BACKUP_FILE_NAME = "telegram-channel-blocker-backup.json"
    }

    private data class HeroState(val title: Int, val subtitle: Int, val background: Int, val tint: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = BlockedChannelRepository(this)
        appLock = AppLock(this)

        setSupportActionBar(binding.toolbar)

        adapter = ChannelAdapter(
            onEdit = { channel -> guardIfStrict { showEditDialog(channel) } },
            onDelete = { channel -> guardIfStrict { deleteChannel(channel) } }
        )

        setupModeToggle()

        binding.btnAddChannel.setOnClickListener { showAddDialog() }

        setupReadinessSection()
        setupLockOverlay()
        setupPackageSection()
        setupDomainSection()
        setupContentDetectionSection()
        setupImageDetectionSection()
        setupBackupSection()

        refreshList()
        refreshPackages()
        refreshDomains()

        // Intent "Share" dari Play Store baru diproses setelah kunci dibuka.
        if (appLock.needsUnlock()) {
            pendingShareIntent = intent
        } else {
            handleShareIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (lockVisible || appLock.needsUnlock()) {
            pendingShareIntent = intent
        } else {
            handleShareIntent(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        enforceLock()
    }

    override fun onResume() {
        super.onResume()
        // Layar penuh rahasia hanya saat di latar belakang/terkunci, supaya
        // tangkapan layar biasa tetap bisa dipakai saat aplikasi terbuka.
        if (!lockVisible) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        refreshList()
        refreshPackages()
        refreshDomains()
        updateServiceStatus()
        updateUninstallProtectionStatus()
        refreshLockStatus()
        refreshBackupStatus()
    }

    override fun onPause() {
        super.onPause()
        // Menyembunyikan pratinjau di daftar aplikasi terbaru kalau kunci aktif.
        if (appLock.isEnabled()) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStop() {
        super.onStop()
        biometricShowing = false
        if (!isChangingConfigurations) appLock.markLeft()
    }

    override fun onDestroy() {
        stopLockoutTicker()
        super.onDestroy()
    }

    // ===================== Daftar & status =====================

    /**
     * Menampilkan SEMUA baris adapter di dalam LinearLayout. Halaman utama
     * sudah bisa di-scroll, jadi daftar tidak perlu RecyclerView sendiri
     * (yang sebelumnya bisa terpotong tingginya di dalam ScrollView).
     */
    @Suppress("UNCHECKED_CAST")
    private fun renderRows(container: android.widget.LinearLayout, rowAdapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>) {
        val a = rowAdapter as androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>
        container.removeAllViews()
        for (i in 0 until a.itemCount) {
            val holder = a.createViewHolder(container, a.getItemViewType(i))
            a.bindViewHolder(holder, i)
            container.addView(holder.itemView)
        }
    }

    private fun refreshList() {
        val channels = repository.getChannels()
        adapter.submitList(channels)
        renderRows(binding.rvChannels, adapter)
        binding.tvEmpty.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
        binding.tvChannelCount.text = getString(R.string.count_channels, channels.size)
    }

    private fun setupReadinessSection() {
        binding.btnOpenAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.tvServiceInstructions.setOnClickListener { openAccessibilitySettings() }
        binding.btnEnableUninstallProtection.setOnClickListener { requestDeviceAdmin() }
        binding.btnAppLock.setOnClickListener { onAppLockButton() }
    }

    private fun openAccessibilitySettings() {
        appLock.markInternalLaunch()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun updateServiceStatus() {
        val enabled = isAccessibilityServiceEnabled()
        binding.tvServiceStatus.text = if (enabled) {
            getString(R.string.service_active)
        } else {
            getString(R.string.service_inactive)
        }
        binding.tvServiceStatus.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.accent else R.color.danger)
        )
        refreshHero()
    }

    /** Kartu status di atas: judul, warna, dan ikon mengikuti mode dan status layanan. */
    private fun refreshHero() {
        val serviceOn = isAccessibilityServiceEnabled()
        val hero = when {
            !serviceOn -> HeroState(
                R.string.hero_title_service_off, R.string.hero_sub_service_off,
                R.color.danger_container, R.color.danger
            )
            currentMode == BlockingMode.OFF -> HeroState(
                R.string.hero_title_off, R.string.hero_sub_off,
                R.color.warn_container, R.color.warn
            )
            currentMode == BlockingMode.STRICT -> HeroState(
                R.string.hero_title_strict, R.string.hero_sub_strict,
                R.color.success_container, R.color.accent
            )
            else -> HeroState(
                R.string.hero_title_active, R.string.hero_sub_active,
                R.color.primary_container, R.color.primary
            )
        }
        binding.tvStatusTitle.setText(hero.title)
        binding.tvStatusSubtitle.setText(hero.subtitle)
        binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, hero.background))
        binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(this, hero.tint))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${TelegramBlockAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        for (component in splitter) {
            if (component.equals(expectedComponent, ignoreCase = true)) return true
        }
        return false
    }

    // ===================== Channel =====================

    /** Set hint pada input sesuai tipe yang dipilih, dan hubungkan RadioGroup ke perubahan hint. */
    private fun setupChannelTypeRadio(dialogView: View) {
        val radioGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.radioChannelType)
        val tilUsername = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilUsername)
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            tilUsername.hint = if (checkedId == R.id.radioChannelName) {
                getString(R.string.hint_channel_name)
            } else {
                getString(R.string.hint_channel_username)
            }
        }
    }

    private fun selectedType(dialogView: View): ChannelType {
        val radioGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.radioChannelType)
        return if (radioGroup.checkedRadioButtonId == R.id.radioChannelName) {
            ChannelType.NAME
        } else {
            ChannelType.USERNAME
        }
    }

    private fun showAddDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_channel, null)
        val etUsername = dialogView.findViewById<TextInputEditText>(R.id.etUsername)
        setupChannelTypeRadio(dialogView)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_add_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val input = etUsername.text?.toString().orEmpty()
                if (input.trim().removePrefix("@").isBlank()) {
                    Toast.makeText(this, R.string.toast_invalid_username, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val added = repository.addChannel(selectedType(dialogView), input)
                if (!added) {
                    Toast.makeText(this, R.string.toast_duplicate, Toast.LENGTH_SHORT).show()
                }
                refreshList()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showEditDialog(channel: BlockedChannel) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_channel, null)
        val etUsername = dialogView.findViewById<TextInputEditText>(R.id.etUsername)
        val radioGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.radioChannelType)
        setupChannelTypeRadio(dialogView)
        etUsername.setText(channel.value)
        radioGroup.check(if (channel.type == ChannelType.NAME) R.id.radioChannelName else R.id.radioUsername)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_edit_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val input = etUsername.text?.toString().orEmpty()
                if (input.trim().removePrefix("@").isBlank()) {
                    Toast.makeText(this, R.string.toast_invalid_username, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                repository.updateChannel(channel, selectedType(dialogView), input)
                refreshList()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ===================== Cegah uninstall (Device Admin) =====================
    // Bagian ini sengaja berdiri sendiri, tidak memanggil apa pun dari
    // BlockedChannelRepository / AccessibilityService, supaya fitur
    // pemblokiran channel yang sudah berjalan tidak ikut terdampak.

    private fun getDevicePolicyManager(): DevicePolicyManager =
        getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun getAdminComponent(): ComponentName =
        ComponentName(this, TgBlockerDeviceAdminReceiver::class.java)

    private fun isDeviceAdminActive(): Boolean {
        return try {
            getDevicePolicyManager().isAdminActive(getAdminComponent())
        } catch (e: Exception) {
            false
        }
    }

    private fun updateUninstallProtectionStatus() {
        val active = isDeviceAdminActive()
        binding.tvUninstallProtectionStatus.text = if (active) {
            getString(R.string.uninstall_protection_active)
        } else {
            getString(R.string.uninstall_protection_inactive)
        }
        binding.tvUninstallProtectionStatus.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.accent else R.color.danger)
        )
        binding.btnEnableUninstallProtection.visibility = if (active) View.GONE else View.VISIBLE
    }

    private fun requestDeviceAdmin() {
        if (isDeviceAdminActive()) return
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getAdminComponent())
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_explanation)
            )
        }
        appLock.markInternalLaunch()
        startActivity(intent)
    }

    // ===================== Mode Mati / Normal / Ketat =====================
    // Mati<->Normal bebas, tanpa gate.
    // Mengaktifkan Ketat (dari Mati atau Normal) WAJIB dikonfirmasi dulu lewat
    // dialog konfirmasi (tidak langsung aktif begitu tombol ditekan).
    // Turun dari Ketat (ke Normal atau Mati) WAJIB lewat gate teks acak dulu.
    // Edit/Hapus channel hanya digate kalau mode saat ini Ketat (lihat guardIfStrict).
    // Mode Mati juga mengunci Chrome dan Play Store (diatur di service).

    private fun setupModeToggle() {
        currentMode = repository.getMode()
        reflectModeUI(currentMode)
        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val requestedMode = when (checkedId) {
                R.id.btnModeOff -> BlockingMode.OFF
                R.id.btnModeStrict -> BlockingMode.STRICT
                else -> BlockingMode.NORMAL
            }
            onModeRequested(requestedMode)
        }
    }

    private fun reflectModeUI(mode: BlockingMode) {
        val id = when (mode) {
            BlockingMode.OFF -> R.id.btnModeOff
            BlockingMode.NORMAL -> R.id.btnModeNormal
            BlockingMode.STRICT -> R.id.btnModeStrict
        }
        binding.toggleMode.check(id)
        binding.tvModeDescription.setText(
            when (mode) {
                BlockingMode.OFF -> R.string.mode_desc_off
                BlockingMode.NORMAL -> R.string.mode_desc_normal
                BlockingMode.STRICT -> R.string.mode_desc_strict
            }
        )
        refreshHero()
    }

    private fun onModeRequested(requestedMode: BlockingMode) {
        if (requestedMode == currentMode) return

        val downgradingFromStrict = currentMode == BlockingMode.STRICT && requestedMode != BlockingMode.STRICT
        val activatingStrict = requestedMode == BlockingMode.STRICT

        when {
            downgradingFromStrict -> {
                // Tahan dulu: kembalikan tampilan ke mode saat ini (Ketat) sampai
                // pengguna berhasil mengisi teks acak dengan benar.
                reflectModeUI(currentMode)
                showRandomTextGate {
                    currentMode = requestedMode
                    repository.setMode(requestedMode)
                    reflectModeUI(requestedMode)
                }
            }
            activatingStrict -> {
                // Tahan dulu: kembalikan tampilan ke mode saat ini sampai
                // pengguna mengonfirmasi lewat dialog, bukan langsung aktif.
                reflectModeUI(currentMode)
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.confirm_strict_title)
                    .setMessage(R.string.confirm_strict_message)
                    .setPositiveButton(R.string.btn_activate) { _, _ ->
                        currentMode = requestedMode
                        repository.setMode(requestedMode)
                        reflectModeUI(requestedMode)
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .setCancelable(false)
                    .show()
            }
            else -> {
                currentMode = requestedMode
                repository.setMode(requestedMode)
                reflectModeUI(requestedMode)
            }
        }
    }

    /** Edit/Hapus channel hanya digate kalau mode saat ini Ketat. */
    private fun guardIfStrict(action: () -> Unit) {
        if (currentMode == BlockingMode.STRICT) {
            showRandomTextGate { action() }
        } else {
            action()
        }
    }

    /**
     * Gate teks acak generic: dipakai untuk menonaktifkan pemblokiran,
     * mengedit channel, dan menghapus channel. [onSuccess] hanya dipanggil
     * kalau pengguna berhasil mengetik ulang teks acak 512 karakter PERSIS,
     * divalidasi karakter demi karakter (tidak bisa tempel/paste, dan
     * layar tidak bisa di-screenshot selama dialog ini terbuka).
     */
    private fun showRandomTextGate(onSuccess: () -> Unit) {
        val target = RandomTextGate.generate()
        var currentIndex = 0

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_random_gate, null)
        val tvCurrentChar = dialogView.findViewById<TextView>(R.id.tvRandomText)
        val tvProgress = dialogView.findViewById<TextView>(R.id.tvGateProgress)
        val tvError = dialogView.findViewById<TextView>(R.id.tvGateError)
        val etInput = dialogView.findViewById<TextInputEditText>(R.id.etRandomTextInput)

        fun showChar(index: Int) {
            tvCurrentChar.text = target[index].toString()
            tvProgress.text = getString(R.string.random_gate_progress, index + 1, target.length)
        }
        showChar(currentIndex)

        // Matikan menu copy/paste/select, supaya tidak bisa tempel karakter
        // dari luar (misal hasil bantuan AI lain).
        val disabledActionMode = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        etInput.customSelectionActionModeCallback = disabledActionMode
        etInput.isLongClickable = false

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setNegativeButton(R.string.btn_cancel, null)
            .setCancelable(false)
            .create()

        // Cegah screenshot & perekaman layar selama dialog ini tampil.
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val typed = s?.toString().orEmpty()
                if (typed.isEmpty()) return

                val typedChar = typed[0]
                if (typedChar == target[currentIndex]) {
                    tvError.visibility = View.GONE
                    currentIndex++
                    etInput.setText("")
                    if (currentIndex == target.length) {
                        dialog.dismiss()
                        onSuccess()
                    } else {
                        showChar(currentIndex)
                    }
                } else {
                    tvError.visibility = View.VISIBLE
                    etInput.setText("")
                    // Penalti: mundur 10 langkah (tidak sampai minus / reset total ke 0).
                    currentIndex = (currentIndex - PENALTY_STEPS).coerceAtLeast(0)
                    showChar(currentIndex)
                }
            }
        })

        dialog.show()
    }

    private fun deleteChannel(channel: BlockedChannel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_delete)
            .setMessage(channel.display())
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                repository.deleteChannel(channel)
                refreshList()
                Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ===================== Aplikasi/Klon Tambahan =====================
    // Package di daftar ini diperlakukan sama seperti klon bawaan di
    // TelegramBlockAccessibilityService: ditutup ke Home saat dibuka,
    // mengikuti mode saat ini. Edit/hapus digate lewat guardIfStrict,
    // sama seperti daftar channel.

    private fun setupPackageSection() {
        packageAdapter = PackageAdapter(
            onDelete = { pkg -> guardIfStrict { deleteCustomPackage(pkg) } }
        )
        binding.btnAddPackage.setOnClickListener { showAddPackageDialog() }
    }

    private fun refreshPackages() {
        val packages = repository.getCustomPackages()
        packageAdapter.submitList(packages)
        renderRows(binding.rvPackages, packageAdapter)
        binding.tvEmptyPackages.visibility = if (packages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddPackageDialog(prefill: String? = null) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_package, null)
        val etPackageName = dialogView.findViewById<TextInputEditText>(R.id.etPackageName)
        if (!prefill.isNullOrBlank()) {
            etPackageName.setText(prefill)
            etPackageName.setSelection(etPackageName.text?.length ?: 0)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_add_package_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val input = etPackageName.text?.toString().orEmpty()
                val added = repository.addCustomPackage(input)
                if (!added) {
                    Toast.makeText(this, R.string.toast_invalid_package, Toast.LENGTH_SHORT).show()
                }
                refreshPackages()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun deleteCustomPackage(pkg: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_delete)
            .setMessage(pkg)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                repository.deleteCustomPackage(pkg)
                refreshPackages()
                Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * Menangani "Share" halaman aplikasi dari Play Store (mis. lewat tombol
     * Share di halaman detail aplikasi). Play Store membagikan teks berisi
     * URL seperti https://play.google.com/store/apps/details?id=com.whatsapp
     * — package name diambil dari parameter "id=" di URL tersebut, lalu
     * dialog Tambah Aplikasi dibuka dengan nilai itu sudah terisi (pengguna
     * tetap perlu menekan Simpan, tidak otomatis ditambahkan).
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val packageId = sharedText?.let { extractPackageIdFromShare(it) }
        if (packageId.isNullOrBlank()) {
            Toast.makeText(this, R.string.toast_share_not_recognized, Toast.LENGTH_SHORT).show()
            return
        }
        showAddPackageDialog(prefill = packageId)
    }

    private fun extractPackageIdFromShare(text: String): String? {
        val match = Regex("id=([a-zA-Z][a-zA-Z0-9_.]*)").find(text) ?: return null
        return match.groupValues[1]
    }

    // ===================== Website yang Diblokir =====================
    // Domain di sini digabung dengan AdultDomainList (bawaan) di service saat
    // membandingkan address bar Chrome. Edit/hapus digate lewat guardIfStrict,
    // sama seperti daftar channel & package.

    private fun setupDomainSection() {
        domainAdapter = DomainAdapter(
            onDelete = { domain -> guardIfStrict { deleteBlockedDomain(domain) } }
        )
        binding.btnAddDomain.setOnClickListener { showAddDomainDialog() }
    }

    private fun refreshDomains() {
        val domains = repository.getBlockedDomains()
        domainAdapter.submitList(domains)
        renderRows(binding.rvDomains, domainAdapter)
        binding.tvEmptyDomains.visibility = if (domains.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddDomainDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_domain, null)
        val etDomainName = dialogView.findViewById<TextInputEditText>(R.id.etDomainName)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_add_domain_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val input = etDomainName.text?.toString().orEmpty()
                val added = repository.addBlockedDomain(input)
                if (!added) {
                    Toast.makeText(this, R.string.toast_invalid_domain, Toast.LENGTH_SHORT).show()
                }
                refreshDomains()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun deleteBlockedDomain(domain: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_delete)
            .setMessage(domain)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                repository.deleteBlockedDomain(domain)
                refreshDomains()
                Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ===================== Deteksi Konten =====================
    // Toggle-nya sendiri digate lewat guardIfStrict cuma saat MEMATIKAN
    // (supaya nggak bisa dipakai buat curang di Mode Ketat); mengaktifkan
    // bebas tanpa gate, sama pola-nya dengan Mode Mati->Normal->Ketat.

    private fun setupContentDetectionSection() {
        binding.switchContentDetection.isChecked = repository.isContentDetectionEnabled()
        binding.switchContentDetection.setOnCheckedChangeListener { _, isChecked ->
            val current = repository.isContentDetectionEnabled()
            if (isChecked == current) return@setOnCheckedChangeListener

            if (!isChecked) {
                // Tahan tampilan (nyalakan lagi) sampai gate selesai/dibatalkan.
                binding.switchContentDetection.isChecked = true
                guardIfStrict {
                    repository.setContentDetectionEnabled(false)
                    binding.switchContentDetection.isChecked = false
                }
            } else {
                repository.setContentDetectionEnabled(true)
            }
        }

        binding.btnViewLog.setOnClickListener { showDetectionLogDialog() }
    }

    private fun setupImageDetectionSection() {
        binding.switchImageDetection.isChecked = repository.isImageDetectionEnabled()
        updateImageDetectionStatusText()

        binding.switchImageDetection.setOnCheckedChangeListener { _, isChecked ->
            val current = repository.isImageDetectionEnabled()
            if (isChecked == current) return@setOnCheckedChangeListener

            if (!isChecked) {
                binding.switchImageDetection.isChecked = true
                guardIfStrict {
                    repository.setImageDetectionEnabled(false)
                    binding.switchImageDetection.isChecked = false
                    updateImageDetectionStatusText()
                }
            } else {
                repository.setImageDetectionEnabled(true)
                updateImageDetectionStatusText()
            }
        }
    }

    private fun updateImageDetectionStatusText() {
        val statusText = when {
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ->
                getString(R.string.image_detection_status_unsupported)
            ImageContentDetectorFactory.get(this).isAvailable() ->
                getString(R.string.image_detection_status_available)
            else ->
                getString(R.string.image_detection_status_unavailable)
        }
        binding.tvImageDetectionStatus.text = statusText
    }

    private fun showDetectionLogDialog() {
        val log = repository.getDetectionLog()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_log, null)
        val tvLogContent = dialogView.findViewById<TextView>(R.id.tvLogContent)

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        tvLogContent.text = if (log.isEmpty()) {
            getString(R.string.dialog_log_empty)
        } else {
            log.joinToString("\n") { entry ->
                "${sdf.format(java.util.Date(entry.timestamp))}  ${entry.pkg}  ${entry.detectionType}"
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_log_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_close, null)
            .setNegativeButton(R.string.btn_clear_log) { _, _ ->
                repository.clearDetectionLog()
            }
            .show()
    }

    // ===================== Kunci Aplikasi (PIN + sidik jari) =====================
    // Kunci hanya menjaga LAYAR aplikasi ini. Layanan pemblokiran tetap jalan
    // di latar belakang tanpa menunggu kunci dibuka.

    private fun setupLockOverlay() {
        val digitKeys = listOf(
            binding.key0 to "0", binding.key1 to "1", binding.key2 to "2",
            binding.key3 to "3", binding.key4 to "4", binding.key5 to "5",
            binding.key6 to "6", binding.key7 to "7", binding.key8 to "8",
            binding.key9 to "9"
        )
        digitKeys.forEach { (button, digit) ->
            button.setOnClickListener { onLockDigit(digit) }
        }
        binding.keyBackspace.setOnClickListener {
            if (pinBuffer.isNotEmpty()) {
                pinBuffer.deleteCharAt(pinBuffer.length - 1)
                renderPinDots()
            }
        }
        binding.keyBackspace.setOnLongClickListener {
            pinBuffer.clear()
            renderPinDots()
            true
        }
        binding.keyBiometric.setOnClickListener { promptBiometric() }
        binding.btnLockForgot.setOnClickListener { onForgotPin() }
    }

    /** Tampilkan atau sembunyikan layar kunci sesuai status sesi. */
    private fun enforceLock() {
        if (appLock.needsUnlock()) showLock() else hideLock()
    }

    private fun showLock() {
        lockVisible = true
        pinBuffer.clear()
        renderPinDots()
        binding.tvLockError.text = ""
        binding.contentRoot.visibility = View.INVISIBLE
        binding.lockOverlay.visibility = View.VISIBLE
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val biometricReady = appLock.isBiometricEnabled() && canUseBiometric()
        binding.keyBiometric.visibility = if (biometricReady) View.VISIBLE else View.INVISIBLE

        if (appLock.lockoutRemainingMs() > 0L) {
            startLockoutTicker()
        } else if (biometricReady) {
            promptBiometric()
        }
    }

    private fun hideLock() {
        lockVisible = false
        stopLockoutTicker()
        binding.lockOverlay.visibility = View.GONE
        binding.contentRoot.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun unlockSuccess() {
        appLock.markUnlocked()
        biometricShowing = false
        pinBuffer.clear()
        hideLock()
        val pending = pendingShareIntent
        pendingShareIntent = null
        if (pending != null) handleShareIntent(pending)
    }

    private fun renderPinDots() {
        val total = maxOf(appLock.pinLength(), pinBuffer.length)
        val dots = (0 until total).joinToString(" ") { index ->
            if (index < pinBuffer.length) "●" else "○"
        }
        binding.tvPinDots.text = dots
    }

    private fun onLockDigit(digit: String) {
        if (appLock.lockoutRemainingMs() > 0L) return
        if (pinBuffer.length >= PinHasher.MAX_LENGTH) return
        pinBuffer.append(digit)
        binding.tvLockError.text = ""
        renderPinDots()
        if (pinBuffer.length == appLock.pinLength()) submitPin()
    }

    private fun submitPin() {
        val pin = pinBuffer.toString()
        pinBuffer.clear()
        if (appLock.verify(pin)) {
            unlockSuccess()
            return
        }
        renderPinDots()
        if (appLock.lockoutRemainingMs() > 0L) {
            startLockoutTicker()
        } else {
            binding.tvLockError.setText(R.string.lock_wrong)
        }
    }

    private fun startLockoutTicker() {
        stopLockoutTicker()
        val ticker = object : Runnable {
            override fun run() {
                val remainingMs = appLock.lockoutRemainingMs()
                if (remainingMs <= 0L) {
                    binding.tvLockError.text = ""
                    lockoutTicker = null
                    return
                }
                val seconds = ((remainingMs + 999L) / 1000L).toInt()
                binding.tvLockError.text = getString(R.string.lock_wait, seconds)
                uiHandler.postDelayed(this, 1000L)
            }
        }
        lockoutTicker = ticker
        ticker.run()
    }

    private fun stopLockoutTicker() {
        lockoutTicker?.let { uiHandler.removeCallbacks(it) }
        lockoutTicker = null
    }

    private fun canUseBiometric(): Boolean =
        BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun hasBiometricHardware(): Boolean =
        BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) !=
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

    private fun promptBiometric() {
        if (!lockVisible || biometricShowing) return
        if (!appLock.isBiometricEnabled() || !canUseBiometric()) return
        if (appLock.lockoutRemainingMs() > 0L) return

        biometricShowing = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    biometricShowing = false
                    unlockSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Gagal/dibatalkan: tetap di layar PIN.
                    biometricShowing = false
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_biometric_title))
            .setNegativeButtonText(getString(R.string.lock_use_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info)
    }

    /** Lupa PIN: kunci hanya bisa dilepas setelah menyelesaikan gate teks acak. */
    private fun onForgotPin() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lock_forgot_title)
            .setMessage(R.string.lock_forgot_message)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                showRandomTextGate {
                    appLock.clear()
                    unlockSuccess()
                    refreshLockStatus()
                    Toast.makeText(this, R.string.toast_lock_disabled, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ----- Pengaturan kunci (kartu Kesiapan & Keamanan) -----

    private fun refreshLockStatus() {
        val enabled = appLock.isEnabled()
        binding.tvAppLockStatus.text = when {
            enabled && appLock.isBiometricEnabled() -> getString(R.string.app_lock_active_bio)
            enabled -> getString(R.string.app_lock_active)
            else -> getString(R.string.app_lock_inactive)
        }
        binding.tvAppLockStatus.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.accent else R.color.text_secondary)
        )
        binding.btnAppLock.setText(if (enabled) R.string.btn_manage else R.string.btn_set)
    }

    private fun onAppLockButton() {
        if (!appLock.isEnabled()) showSetPinDialog(isChange = false) else showLockMenu()
    }

    private fun showSetPinDialog(isChange: Boolean) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin, null)
        val tvHint = dialogView.findViewById<TextView>(R.id.tvPinHint)
        val tilPin1 = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilPin1)
        val etPin1 = dialogView.findViewById<TextInputEditText>(R.id.etPin1)
        val etPin2 = dialogView.findViewById<TextInputEditText>(R.id.etPin2)
        tvHint.setText(R.string.dialog_pin_new_hint)
        tilPin1.hint = getString(R.string.hint_pin_new)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (isChange) R.string.dialog_pin_change_title else R.string.dialog_pin_set_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin1 = etPin1.text?.toString().orEmpty()
                val pin2 = etPin2.text?.toString().orEmpty()
                when {
                    !PinHasher.isValidPin(pin1) ->
                        Toast.makeText(this, R.string.toast_pin_invalid, Toast.LENGTH_SHORT).show()
                    pin1 != pin2 ->
                        Toast.makeText(this, R.string.toast_pin_mismatch, Toast.LENGTH_SHORT).show()
                    else -> {
                        appLock.setPin(pin1)
                        appLock.markUnlocked()
                        Toast.makeText(this, R.string.toast_pin_saved, Toast.LENGTH_SHORT).show()
                        refreshLockStatus()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    /** Meminta PIN yang sekarang; [onVerified] hanya dipanggil kalau benar. */
    private fun askCurrentPin(onVerified: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin, null)
        val tvHint = dialogView.findViewById<TextView>(R.id.tvPinHint)
        val tilPin1 = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilPin1)
        val etPin1 = dialogView.findViewById<TextInputEditText>(R.id.etPin1)
        val tilPin2 = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilPin2)
        tvHint.setText(R.string.dialog_pin_current_hint)
        tilPin1.hint = getString(R.string.hint_pin_current)
        tilPin2.visibility = View.GONE

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_pin_current_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_ok, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val waitMs = appLock.lockoutRemainingMs()
                if (waitMs > 0L) {
                    val seconds = ((waitMs + 999L) / 1000L).toInt()
                    Toast.makeText(this, getString(R.string.toast_pin_wait, seconds), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val pin = etPin1.text?.toString().orEmpty()
                if (appLock.verify(pin)) {
                    dialog.dismiss()
                    onVerified()
                } else {
                    etPin1.setText("")
                    Toast.makeText(this, R.string.toast_pin_wrong, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun showLockMenu() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels += getString(R.string.lock_menu_change)
        actions += { askCurrentPin { showSetPinDialog(isChange = true) } }

        if (hasBiometricHardware()) {
            if (appLock.isBiometricEnabled()) {
                labels += getString(R.string.lock_menu_biometric_off)
                actions += {
                    appLock.setBiometricEnabled(false)
                    refreshLockStatus()
                }
            } else {
                labels += getString(R.string.lock_menu_biometric_on)
                actions += {
                    if (canUseBiometric()) {
                        appLock.setBiometricEnabled(true)
                        refreshLockStatus()
                    } else {
                        Toast.makeText(this, R.string.toast_biometric_unavailable, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        labels += getString(R.string.lock_menu_autolock, autoLockLabel(appLock.autoLockSeconds()))
        actions += { showAutoLockChoices() }

        labels += getString(R.string.lock_menu_disable)
        actions += {
            // Di Mode Ketat, mematikan kunci juga lewat teks acak, lalu PIN.
            guardIfStrict {
                askCurrentPin {
                    appLock.clear()
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    refreshLockStatus()
                    Toast.makeText(this, R.string.toast_lock_disabled, Toast.LENGTH_SHORT).show()
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lock_menu_title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private val autoLockValues = intArrayOf(0, 30, 60, 300)

    private fun autoLockLabel(seconds: Int): String = when (seconds) {
        0 -> getString(R.string.autolock_now)
        30 -> getString(R.string.autolock_30s)
        60 -> getString(R.string.autolock_1m)
        300 -> getString(R.string.autolock_5m)
        else -> "$seconds s"
    }

    private fun showAutoLockChoices() {
        val labels = autoLockValues.map { autoLockLabel(it) }.toTypedArray()
        val checked = autoLockValues.indexOf(appLock.autoLockSeconds()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.autolock_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                appLock.setAutoLockSeconds(autoLockValues[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ===================== Cadangan & Pemulihan =====================
    // Cadangan ditulis ke file yang dipilih lewat pemilih file Android. Kalau
    // pengguna memilih folder di Google Drive, file ada di Drive-nya. Pemulihan
    // MENGGANTI seluruh data, jadi digate lewat guardIfStrict supaya tidak bisa
    // dipakai untuk "reset diam-diam" saat Mode Ketat aktif.

    private fun setupBackupSection() {
        binding.btnExport.setOnClickListener { showExportDialog() }
        binding.btnImport.setOnClickListener { showImportDialog() }
        binding.btnBackupChoose.setOnClickListener { chooseBackupLocation() }
        binding.btnBackupNow.setOnClickListener { runBackupNow() }
        binding.btnRestore.setOnClickListener { onRestoreClicked() }

        binding.switchAutoBackup.isChecked = BackupManager.isAutoEnabled(this)
        binding.switchAutoBackup.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == BackupManager.isAutoEnabled(this)) return@setOnCheckedChangeListener
            if (isChecked && BackupManager.getTarget(this) == null) {
                // Belum ada lokasi: kembalikan saklar, minta pilih lokasi dulu.
                binding.switchAutoBackup.isChecked = false
                chooseBackupLocation()
            } else {
                BackupManager.setAutoEnabled(this, isChecked)
                if (isChecked) runBackupNow()
            }
        }
        refreshBackupStatus()
    }

    private fun refreshBackupStatus() {
        val target = BackupManager.describeTarget(this)
        binding.tvBackupStatus.text = if (target == null) {
            getString(R.string.backup_status_none)
        } else {
            getString(R.string.backup_status_target, target)
        }
        binding.btnBackupChoose.setText(
            if (target == null) R.string.btn_backup_choose else R.string.btn_backup_choose_change
        )

        val error = BackupManager.lastError(this)
        val lastOk = BackupManager.lastSuccessAt(this)
        binding.tvBackupLast.text = when {
            target == null -> ""
            error != null -> getString(R.string.backup_last_error, error)
            lastOk > 0L -> getString(R.string.backup_last_ok, BackupManager.formatTime(lastOk))
            else -> getString(R.string.backup_last_never)
        }
        binding.tvBackupLast.visibility = if (target == null) View.GONE else View.VISIBLE

        val auto = BackupManager.isAutoEnabled(this)
        if (binding.switchAutoBackup.isChecked != auto) binding.switchAutoBackup.isChecked = auto
    }

    private fun chooseBackupLocation() {
        appLock.markInternalLaunch()
        createBackupLauncher.launch(BACKUP_FILE_NAME)
    }

    private fun onBackupLocationChosen(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Tidak semua penyedia mendukung izin permanen; tetap lanjut.
        }
        BackupManager.setTarget(this, uri)
        BackupManager.setAutoEnabled(this, true)
        binding.switchAutoBackup.isChecked = true
        runBackupNow()
    }

    private fun runBackupNow() {
        if (BackupManager.getTarget(this) == null) {
            Toast.makeText(this, R.string.toast_backup_no_target, Toast.LENGTH_SHORT).show()
            return
        }
        val appContext = applicationContext
        Thread {
            val ok = BackupManager.backupNow(appContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshBackupStatus()
                if (ok) {
                    Toast.makeText(this, R.string.toast_backup_ok, Toast.LENGTH_SHORT).show()
                } else {
                    val reason = BackupManager.lastError(appContext).orEmpty()
                    Toast.makeText(this, getString(R.string.toast_backup_failed, reason), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun onRestoreClicked() {
        val target = BackupManager.getTarget(this)
        if (target == null) {
            pickRestoreFile()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_choose_title)
            .setItems(
                arrayOf(
                    getString(R.string.restore_from_current),
                    getString(R.string.restore_from_file)
                )
            ) { _, which ->
                if (which == 0) restoreFromUri(target) else pickRestoreFile()
            }
            .show()
    }

    private fun pickRestoreFile() {
        appLock.markInternalLaunch()
        openRestoreLauncher.launch(arrayOf("*/*"))
    }

    /** Membaca file di latar belakang, menampilkan ringkasan, lalu minta konfirmasi. */
    private fun restoreFromUri(uri: Uri) {
        val appContext = applicationContext
        Thread {
            val text: String? = try {
                BackupManager.readText(appContext, uri)
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val reason = e.message ?: e.javaClass.simpleName
                    Toast.makeText(this, getString(R.string.toast_restore_read_failed, reason), Toast.LENGTH_LONG).show()
                }
                null
            }
            if (text != null) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    confirmRestore(text)
                }
            }
        }.start()
    }

    private fun confirmRestore(text: String) {
        val preview = repository.previewImport(text)
        if (preview == null) {
            Toast.makeText(this, R.string.toast_restore_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val modeName = when (preview.mode) {
            BlockingMode.OFF -> getString(R.string.mode_off)
            BlockingMode.NORMAL -> getString(R.string.mode_normal)
            BlockingMode.STRICT -> getString(R.string.mode_strict)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_restore_title)
            .setMessage(
                getString(
                    R.string.dialog_restore_message,
                    preview.channels, preview.packages, preview.domains, modeName
                )
            )
            .setPositiveButton(R.string.btn_restore_confirm) { _, _ ->
                guardIfStrict { applyImport(text) }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ----- Export / Import berbasis teks (tanpa file) -----

    private fun showExportDialog() {
        val data = repository.exportData()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_export, null)
        dialogView.findViewById<TextView>(R.id.tvExportData).text = data

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_export_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_copy) { _, _ ->
                copyToClipboard(data)
                Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.btn_share) { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, data)
                }
                appLock.markInternalLaunch()
                startActivity(Intent.createChooser(shareIntent, getString(R.string.dialog_export_title)))
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    }

    private fun showImportDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_import, null)
        val etImportData = dialogView.findViewById<TextInputEditText>(R.id.etImportData)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_import_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_import) { _, _ ->
                val text = etImportData.text?.toString().orEmpty()
                // Digate: kalau mode saat ini Ketat, harus lewat teks acak dulu
                // sebelum data (termasuk mode) benar-benar diganti.
                guardIfStrict { applyImport(text) }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun applyImport(text: String) {
        val success = repository.importData(text)
        if (success) {
            currentMode = repository.getMode()
            reflectModeUI(currentMode)
            refreshList()
            refreshPackages()
            refreshDomains()
            setupContentDetectionSection()
            setupImageDetectionSection()
            Toast.makeText(this, R.string.toast_import_success, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.toast_import_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
