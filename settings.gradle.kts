rootProject.name = "agent-dashboard-ios"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform pulls a few androidx libraries that only Google's repository hosts.
        google()
    }
}

include(":shared")
include(":composeApp")
include(":androidApp")

// The black-box parity suite for the server rewrite. Plain JVM; it talks to a running server over HTTP.
include(":server-parity")
project(":server-parity").projectDir = file("server/parity")
