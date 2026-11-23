plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sany.cloudstream.anilight.abi"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Register the real provider files with the Kotlin source set so the Kotlin
// source filter can exclude only the upstream Plugin host subclass.
kotlin {
    sourceSets.getByName("main").kotlin.apply {
        srcDir("../cloudstream-original/AniLight/src/main/kotlin/com/anilight")
        exclude("**/AniLightPlugin.kt")
    }
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":cloudstream-library"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
