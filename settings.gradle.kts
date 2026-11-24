rootProject.name = "agent-dashboard-ios"

pluginManagement {
    // Same idea as the repositories below. The plugin portal passes requests on to Maven Central,
    // so it is kept off the androidx and com.android groups too.
    val notGoogleOnly: RepositoryContentDescriptor.() -> Unit = {
        excludeGroupByRegex("androidx(\\..*)?")
        excludeGroupByRegex("com\\.android(\\..*)?")
    }
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "MavenCentralMirror"
            content(notGoogleOnly)
        }
        gradlePluginPortal { content(notGoogleOnly) }
        mavenCentral { content(notGoogleOnly) }
        google()
    }
}

dependencyResolutionManagement {
    // Maven Central rate-limits busy CI machines (HTTP 429), and then Gradle gives up on it. So
    // Google's copy of Maven Central comes first, and Maven Central is the fallback for anything
    // the copy hasn't picked up yet (the copy answers 404 and Gradle moves on).
    // Only Google's own repository has androidx and com.android, so the others aren't asked for them.
    val notGoogleOnly: RepositoryContentDescriptor.() -> Unit = {
        excludeGroupByRegex("androidx(\\..*)?")
        excludeGroupByRegex("com\\.android(\\..*)?")
    }
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "MavenCentralMirror"
            content(notGoogleOnly)
        }
        mavenCentral { content(notGoogleOnly) }
        // Compose Multiplatform pulls a few androidx libraries that only Google's repository hosts.
        google()
    }
}

include(":shared")
include(":composeApp")
include(":androidApp")

// The Kotlin server: a library the desktop app can host, plus a standalone entry point and CLI.
include(":server")

// The black-box parity suite for the server rewrite. Plain JVM; it talks to a running server over HTTP.
include(":server-parity")
project(":server-parity").projectDir = file("server/parity")
