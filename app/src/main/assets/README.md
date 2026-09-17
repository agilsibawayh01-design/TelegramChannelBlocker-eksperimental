# Model Content Detection (belum terpasang)

Folder ini kosong secara sengaja. Fitur "Deteksi Gambar (AI)" di aplikasi
akan tetap OFF/tidak tersedia sampai kamu menaruh file model TFLite di
folder ini dengan nama PERSIS:

    content_detector.tflite

Lihat komentar di `TfliteImageContentDetector.kt` untuk rekomendasi sumber
model, cara cek lisensinya, dan cara menyesuaikan kode kalau bentuk
output model yang kamu pasang berbeda.

Aplikasi TIDAK akan crash kalau file ini tidak ada — deteksi gambar cuma
akan melaporkan dirinya "tidak tersedia" dan fitur teks/domain/dll tetap
jalan normal seperti biasa.
