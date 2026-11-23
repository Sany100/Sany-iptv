plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    // Declare the multiplatform plugin at the root so the upstream library
    // can resolve its versioned alias without an unknown classpath version.
    id("org.jetbrains.kotlin.multiplatform") version "2.4.0" apply false
    id("com.google.gms.google-services") version "4.6.0" apply false
}
