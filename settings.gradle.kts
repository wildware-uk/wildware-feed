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
