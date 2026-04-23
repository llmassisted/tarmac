plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.tarmac"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.tarmac"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    // libplist is now vendored under native/libplist and
                    // OpenSSL ships via the prefab AAR, so the JNI lib links
                    // UxPlay's RAOP/RTSP server by default.
                    "-DTARMAC_LINK_LIBAIRPLAY=ON"
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        prefab = true
    }

    // Release signing reads from ~/.gradle/gradle.properties (or -P gradle
    // flags) so the keystore path and passwords never hit the repo. Required
    // keys when assembling release:
    //   TARMAC_RELEASE_STORE_FILE     — absolute path to the .jks keystore
    //   TARMAC_RELEASE_STORE_PASSWORD — keystore password
    //   TARMAC_RELEASE_KEY_ALIAS      — key alias inside the keystore
    //   TARMAC_RELEASE_KEY_PASSWORD   — key password
    // If any are missing, the release signingConfig is left null and
    // assembleRelease will produce an unsigned APK (still installable only
    // after manual signing).
    val tarmacStoreFile = (findProperty("TARMAC_RELEASE_STORE_FILE") as String?)?.let { file(it) }
    val tarmacStorePassword = findProperty("TARMAC_RELEASE_STORE_PASSWORD") as String?
    val tarmacKeyAlias = findProperty("TARMAC_RELEASE_KEY_ALIAS") as String?
    val tarmacKeyPassword = findProperty("TARMAC_RELEASE_KEY_PASSWORD") as String?
    val tarmacReleaseSigning = if (
        tarmacStoreFile != null && tarmacStoreFile.exists() &&
        tarmacStorePassword != null && tarmacKeyAlias != null && tarmacKeyPassword != null
    ) {
        signingConfigs.create("release") {
            storeFile = tarmacStoreFile
            storePassword = tarmacStorePassword
            keyAlias = tarmacKeyAlias
            keyPassword = tarmacKeyPassword
        }
    } else null

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            tarmacReleaseSigning?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources.excludes += listOf(
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.leanback.preference)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    // Prefab AAR providing OpenSSL 3.x (libcrypto + libssl) for the Android
    // NDK. Consumed via CMake `find_package(openssl REQUIRED CONFIG)` — see
    // app/src/main/cpp/CMakeLists.txt. See gradle/libs.versions.toml for why
    // this uses the community io.github.ronickg coordinates instead of
    // com.android.ndk.thirdparty (which has no 3.x release).
    implementation(libs.ndk.openssl)
}
