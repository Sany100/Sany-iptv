plugins {
    id("com.android.application")
    // Keep Kotlin Android scoped to the app module. The CloudStream library
    // applies Kotlin Multiplatform from its own upstream version catalog.
    id("org.jetbrains.kotlin.android") version "2.4.0"
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
    compileSdk = 36

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
        minSdk = 23
        targetSdk = 35
        versionCode = providers.gradleProperty("APP_VERSION_CODE").orElse("365").get().toInt()
        versionName = providers.gradleProperty("APP_VERSION_NAME").orElse("1.2.365").get()
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
    implementation(project(":cloudstream-library"))

    if (file("google-services.json").exists()) {
        implementation(platform("com.google.firebase:firebase-bom:34.14.1"))
        implementation("com.google.firebase:firebase-analytics")
        implementation("com.google.firebase:firebase-messaging")
    }
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.3")
    implementation("androidx.media3:media3-exoplayer-dash:1.9.3")
    implementation("androidx.media3:media3-ui:1.9.3")
    implementation("commons-net:commons-net:3.11.1")
}
