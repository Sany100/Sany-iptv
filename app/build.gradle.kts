plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sany.iptv"
    compileSdk = 35

    val releaseStoreFile = System.getenv("SANY_KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("SANY_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("SANY_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("SANY_KEY_PASSWORD")

    signingConfigs {
        create("release") {
            if (!releaseStoreFile.isNullOrBlank()) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    defaultConfig {
        applicationId = "com.sany.iptv"
        minSdk = 23
        targetSdk = 35
        versionCode = providers.gradleProperty("APP_VERSION_CODE").orElse("2").get().toInt()
        versionName = providers.gradleProperty("APP_VERSION_NAME").orElse("1.1").get()
    }

    // Separate installable packages: mobile keeps the existing package ID;
    // TV gets its own ID so both editions can coexist on one Android device.
    flavorDimensions += "device"
    productFlavors {
        create("mobile") {
            dimension = "device"
        }
        create("tv") {
            dimension = "device"
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!releaseStoreFile.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
