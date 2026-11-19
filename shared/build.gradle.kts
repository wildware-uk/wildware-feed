import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(21)

    // The JVM target exists so the shared logic's tests run in milliseconds on any machine,
    // including the Linux box this is developed on, which cannot run iOS tests.
    jvm()

    // The Android app (androidApp/) runs the same engine. It has no code of its own here.
    android {
        namespace = "dev.wildware.agents.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    // One framework for the iPhone and one for the watch. watchosArm64 is arm64_32, the code most
    // watches run; watchosDeviceArm64 is the plain arm64 that newer watches can run.
    val xcframework = XCFramework("Shared")
    listOf(
        iosArm64(), iosSimulatorArm64(),
        watchosArm64(), watchosDeviceArm64(), watchosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            // Swift calls into Kotlin from widget, Siri and notification threads, not only the main one.
            binaryOption("objcExportSuspendFunctionLaunchThreadRestriction", "none")
            xcframework.add(this)
        }
    }

    // The web app (composeApp's wasmJs). The tests run in Node, which needs no browser.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        // appleMain is iPhone and watch together: both use Apple's own networking.
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.coroutines.test)
        }
    }
}
