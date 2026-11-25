import java.net.URL
import java.security.MessageDigest

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


// Bundle the pinned Live Sports provider inside every APK so first launch can
// install it locally without waiting for a provider download.
val playZtvAssetsDir = layout.buildDirectory.dir("generated/liveSportsAssets")
val bundlePlayZtvProvider by tasks.registering {
    val bundledPackage = playZtvAssetsDir.map { it.file("live_sports/PlayZTVProvider.cs3") }
    outputs.file(bundledPackage)
    doLast {
        val output = bundledPackage.get().asFile
        output.parentFile.mkdirs()
        val packageUrl = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/builds/PlayZTVProvider.cs3"
        val expectedSha256 = "8541a6dbf0c8fdd6dbf698548e85a856cdd11be27d99db0ee3a1ccda5f4226b5"
        URL(packageUrl).openStream().use { input ->
            output.outputStream().buffered().use { destination -> input.copyTo(destination) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(output.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == expectedSha256) { "Bundled PlayZTVProvider checksum mismatch: $digest" }
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
    sourceSets.getByName("main").java.srcDir("../cloudstream-original/AniLight/src/main/kotlin")
    sourceSets.getByName("main").assets.srcDir(playZtvAssetsDir)

    defaultConfig {
        minSdk = 23
        targetSdk = 35
        versionCode = providers.gradleProperty("APP_VERSION_CODE").orElse("452").get().toInt()
        versionName = providers.gradleProperty("APP_VERSION_NAME").orElse("1.2.452").get()
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

tasks.named("preBuild").configure { dependsOn(bundlePlayZtvProvider) }

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
    implementation("androidx.media3:media3-datasource-okhttp:1.9.3")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.Blatzar:NiceHttp:0.4.18")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    // CloudStream plugin DEX files resolve Gson through the host app classloader.
    // Package Gson explicitly so plugin references such as com.google.gson.JsonParser resolve at runtime.
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
