# Telegram Channel Blocker

> **Gabungan Safe Telegram** (varian eksperimental + NSFW tetap utuh).
> Package tetap `com.mas.tgblocker`, jadi bisa dipasang menimpa versi lama.
>
> - **Blokir Global Search Telegram** (dari Safe Telegram versi fungsional, logika deteksi sama persis).
>   Aktif di mode Normal dan Ketat; mode Mati menonaktifkan semuanya. Lihat `GlobalSearchGuard.kt`.
> - **Mode Ketat menjaga halaman Pengaturan aplikasi ini sendiri**: saklar Aksesibilitas,
>   Info Aplikasi (Copot/Paksa berhenti/Nonaktifkan), dan halaman Admin Perangkat. Halaman lain
>   di Pengaturan tetap bebas dibuka. Lihat `StrictSettingsGuard.kt`.
> - Cuma satu layanan Aksesibilitas yang perlu aktif. **Safe Telegram lama boleh dicopot.**


Aplikasi Android baru dan terpisah total dari proyek SafeTelegram sebelumnya.
Memblokir channel Telegram tertentu (berdasarkan daftar `@username` yang Anda
kelola sendiri) menggunakan AccessibilityService — tanpa server, tanpa akun,
tanpa koneksi internet.

## Cara upload ke repository GitHub baru

Catatan: Claude tidak memiliki akses untuk membuat/mengedit repository GitHub
Anda secara langsung, sehingga langkah ini perlu dilakukan sendiri.

1. Buat repository baru bernama `TelegramChannelBlocker` di GitHub (kosong,
   tanpa README/gitignore bawaan).
2. Di komputer Anda (atau melalui GitHub web upload), unggah seluruh isi
   folder project ini ke branch `main`.
3. Jika lewat terminal:
   ```bash
   cd TelegramChannelBlocker
   git init
   git add .
   git commit -m "Initial commit: Telegram Channel Blocker"
   git branch -M main
   git remote add origin https://github.com/<username-anda>/TelegramChannelBlocker.git
   git push -u origin main
   ```
4. Setelah push, buka tab **Actions** di GitHub — workflow "Build Debug APK"
   akan berjalan otomatis. Bisa juga dipicu manual lewat tombol
   **Run workflow** (workflow_dispatch).
5. Setelah build selesai, unduh APK dari bagian **Artifacts** pada hasil
   workflow run tersebut.

## Cara pakai di HP

1. Install APK debug hasil build.
2. Buka aplikasi **Telegram Channel Blocker**.
3. Tap teks instruksi (atau buka Setelan → Aksesibilitas) lalu aktifkan
   **Telegram Channel Blocker** di daftar layanan aksesibilitas.
4. Kelola daftar channel yang diblokir lewat tombol **+ Tambah Channel**.
   `@argo` sudah ada sebagai entri default.
5. Nyalakan/matikan pemblokiran lewat switch di bagian atas kapan saja.

## Cara kerja deteksi

- **Channel biasa**: dicocokkan lewat pola `@username` yang ketat (word
  boundary), supaya channel lain yang kebetulan punya nama mirip tidak ikut
  terblokir.
- **Argo Search**: dideteksi lewat kombinasi signature — frasa "Argo Search",
  atau subtitle "Telegram's search engine" yang muncul bersamaan dengan kata
  "argo" di layar yang sama. Kata "search" sendirian **tidak pernah** memicu
  pemblokiran, untuk mencegah false positive.
- Service hanya aktif untuk package Telegram (termasuk beberapa varian/fork
  umum) — tidak menyentuh aplikasi lain sama sekali.

## Struktur proyek

```
app/src/main/java/com/mas/tgblocker/
  MainActivity.kt
  TelegramBlockAccessibilityService.kt
  BlockedChannel.kt
  BlockedChannelRepository.kt
  ChannelDetector.kt
  ArgoSearchDetector.kt
  ChannelAdapter.kt
app/src/main/res/...
.github/workflows/build.yml
```

## v2: tampilan baru + fitur tambahan
- Tampilan Material 3, mendukung mode terang/gelap.
- Kunci aplikasi (PIN 4–8 digit + sidik jari opsional, kunci otomatis, jeda setelah salah PIN). Lupa PIN -> lewat gate teks acak.
- Cadangan/pemulihan lewat pemilih file Android (pilih folder Google Drive), otomatis saat data berubah + tiap 12 jam.
- Mode Mati: Chrome dan Play Store otomatis ditutup ke Home. Normal/Ketat: kembali normal.
