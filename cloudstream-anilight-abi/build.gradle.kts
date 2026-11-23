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

    // Compile the actual AniLight provider implementation against the pinned
    // CloudStream library ABI. Exclude the Android Plugin subclass for now:
    // its upstream host class lives in CloudStream's app module, not :library.
    sourceSets.getByName("main").java.apply {
        srcDir("../cloudstream-original/AniLight/src/main/kotlin/com/anilight")
        exclude("AniLightPlugin.kt")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":cloudstream-library"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    // AniLight model source directly uses Jackson annotations; pin to the
    // exact version declared by the upstream CloudStream version catalog.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
