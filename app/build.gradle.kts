plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ----- Signing key permanen -----
// Tujuan: supaya APK dari build CI berikutnya bisa di-INSTALL SEBAGAI UPDATE
// (menimpa versi lama), bukan harus uninstall dulu. Android hanya mengizinkan
// update kalau signature APK baru == signature APK yang sedang terpasang.
// Sebelumnya buildType "debug" memakai debug-keystore otomatis yang DIBUAT
// ULANG setiap kali runner GitHub Actions baru dijalankan (signature beda
// tiap build) — itu sebabnya harus uninstall+install terus.
//
// Sekarang buildType "debug" di-override untuk pakai keystore PERMANEN, yang
// path & passwordnya diambil dari environment variable (diisi lewat GitHub
// Secrets di workflow, TIDAK ditulis di sini). Kalau environment variable
// atau file keystore-nya tidak ada (mis. build lokal biasa tanpa secrets),
// otomatis fallback ke debug-keystore default Android seperti biasa —
// supaya project tetap bisa di-build di komputer siapa pun tanpa setup
// tambahan, cuma APK-nya nanti tidak akan "menyambung" sebagai update dari
// APK hasil CI (perlu uninstall sekali kalau dipakai campur).
val persistentKeystorePath = System.getenv("TGBLOCKER_KEYSTORE_PATH")
val persistentKeystoreFile = persistentKeystorePath?.let { file(it) }
val hasPersistentKeystore = persistentKeystoreFile?.exists() == true

android {
    namespace = "com.mas.tgblocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mas.tgblocker"
        minSdk = 26
        targetSdk = 34
        // versionCode WAJIB naik terus supaya Android mau menganggapnya update.
        // Diisi dari nomor run CI (GITHUB_RUN_NUMBER, otomatis naik tiap run).
        // Build lokal (tanpa env ini) fallback ke 1.
        versionCode = System.getenv("TGBLOCKER_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "1.0.${System.getenv("TGBLOCKER_VERSION_CODE") ?: "0"}"
    }

    signingConfigs {
        if (hasPersistentKeystore) {
            create("persistent") {
                storeFile = persistentKeystoreFile
                storePassword = System.getenv("TGBLOCKER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TGBLOCKER_KEY_ALIAS")
                keyPassword = System.getenv("TGBLOCKER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (hasPersistentKeystore) {
                signingConfig = signingConfigs.getByName("persistent")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasPersistentKeystore) {
                signingConfig = signingConfigs.getByName("persistent")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
