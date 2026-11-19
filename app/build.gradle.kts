plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase is enabled when app/google-services.json is supplied (local file or CI secret).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
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
        minSdk = 24
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    if (file("google-services.json").exists()) {
        implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
        implementation("com.google.firebase:firebase-analytics")
    }
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
