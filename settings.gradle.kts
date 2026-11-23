pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.android.")) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    // Use the exact dependency/plugin catalog from the pinned CloudStream source.
    versionCatalogs {
        create("libs") {
            from(files("cloudstream-upstream/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "SANY IPTV"
include(":app")
include(":cloudstream-library")
project(":cloudstream-library").projectDir = file("cloudstream-upstream/library")

// Isolated ABI compile gate: not packaged into the SANY APK.
include(":cloudstream-anilight-abi")
