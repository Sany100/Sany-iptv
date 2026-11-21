plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase is enabled when app/google-services.json is supplied (local file or CI secret).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// The Firebase config currently registers the mobile package only.
// Skip Google Services processing for the separate Android TV package.
tasks.configureEach {
    if (name.startsWith("processTv") && name.endsWith("GoogleServices")) {
        enabled = false
    }
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
        // Android 6.0 (Marshmallow), API 23, is the minimum installable version.
        // Newer Android versions are not capped here; test on actual devices.
        minSdk = 23
        targetSdk = 35
        versionCode = providers.gradleProperty("APP_VERSION_CODE").orElse("3").get().toInt()
        versionName = providers.gradleProperty("APP_VERSION_NAME").orElse("1.2").get()
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
        // BoM 34.14.1 maps Firebase Analytics to 23.2.0, avoiding the API 24
        // minimum introduced by Analytics 24.0.0 and retaining Android 6 support.
        implementation(platform("com.google.firebase:firebase-bom:34.14.1"))
        implementation("com.google.firebase:firebase-analytics")
        implementation("com.google.firebase:firebase-messaging")
    }
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("commons-net:commons-net:3.11.1")
}
