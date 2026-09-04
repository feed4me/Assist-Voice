plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nikolay.assistvoice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nikolay.assistvoice"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.11"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        // AppCompat ships translations for ~80 locales. Keeping only the two
        // this app actually uses trims the APK and, more usefully on a watch,
        // shrinks the resource table the process keeps in memory.
        resourceConfigurations += listOf("ru", "en")
    }

    // Release builds get signed with a dedicated keystore only when one is
    // supplied via env vars — that's release.yml in CI, decoding the
    // RELEASE_KEYSTORE_BASE64 secret to a file first (see that workflow and
    // README's "Автообновление" section for the one-time setup). Every
    // release from CI must be signed with the *same* key, or Android refuses
    // to install a new tag as an update over the previous one ("signatures
    // don't match" — an uninstall-and-reinstall, exactly what auto-update is
    // supposed to avoid). Locally, without those env vars set, this
    // signingConfig is simply never attached to the release build type below
    // — building assembleRelease locally still works, it just comes out
    // unsigned, which is fine since README only asks people to build
    // assembleDebug.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Needed for BuildConfig.VERSION_NAME, read by UpdateChecker to know
    // what version to compare the latest GitHub release against.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf("META-INF/*.kotlin_module")
        }
        // AGP's default (compressed, mmap-loaded-from-the-APK) native lib
        // packaging never extracts .so files to a real path on disk —
        // fine for actual shared libraries the JVM/native code dlopen()s,
        // but AdbUpdateInstaller runs jniLibs/*/libadb.so as a subprocess
        // via ProcessBuilder, which needs a real file at
        // applicationInfo.nativeLibraryDir. Legacy packaging is what
        // forces that extraction at install time.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")

    // Pure-Java QR encoder for the info page's repo link — no Android
    // dependency of its own, so it doesn't pull in anything else.
    implementation("com.google.zxing:core:3.5.3")
}
