plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/** The version, from `agents.version` in gradle.properties: the same number as the desktop and iPhone apps. */
val agentsVersion = providers.gradleProperty("agents.version").get()

/** 1.2.3 becomes 1002003: each part gets three digits, so every release counts up. */
fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = version.substringBefore('-').split('.').map { it.toInt() }
    return major * 1_000_000 + minor * 1_000 + patch
}

/**
 * The release key, when this machine has one: CI writes it from secrets (see docs/INSTALL.md).
 * Without it a release build comes out unsigned, and CI does not publish it.
 */
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull?.let(::file)?.takeIf { it.exists() }

android {
    namespace = "dev.wildware.agents.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // The same hidden id family as the iPhone app (dev.wildware.agents) and the Mac app.
        applicationId = "dev.wildware.agents"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionName = agentsVersion
        versionCode = versionCodeOf(agentsVersion)
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            // Sits beside a release install, so testing never replaces the real app.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/previous-compilation-data.bin")
    }

    testOptions {
        // The storage and link tests run on the computer, against Android's stub classes.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":shared"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit4)
}
