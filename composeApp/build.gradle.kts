import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    // Mac, Windows and Linux all run this one JVM build.
    jvm("desktop")

    // The iPhone app links this one framework. It carries `shared` too (exported, so Swift sees the
    // same types the widgets and extensions use), because two Kotlin frameworks in one app would each
    // bring their own Kotlin runtime and their own copy of `shared`. Xcode builds it through
    // `embedAndSignAppleFrameworkForXcode`, for just the device or simulator it is building.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            export(project(":shared"))
            // Swift calls into Kotlin from notification and Siri threads, not only the main one.
            binaryOption("objcExportSuspendFunctionLaunchThreadRestriction", "none")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.resources)
            implementation(libs.markdown.renderer.m3)
            implementation(libs.markdown.renderer.code)
            implementation(libs.highlights)
            implementation(libs.ktor.client.core)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.datetime)
        }
        val desktopMain by getting {
            // The window icon is the installers' own picture, so the two never drift apart.
            resources.srcDir("packaging/icons")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
                implementation(libs.ktor.client.okhttp)
            }
        }
        iosMain.dependencies {
            // `api` so the framework can export it to Swift.
            api(project(":shared"))
            implementation(libs.ktor.client.darwin)
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(compose.desktop.currentOs)
                implementation(libs.compose.ui.test)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "dev.wildware.agents.app.resources"
}

compose.desktop {
    application {
        mainClass = "dev.wildware.agents.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Agents"
            packageVersion = "1.0.0"
        }
    }
}

// Only the PNG is needed at run time; the .icns and .ico are for the installers.
tasks.named<ProcessResources>("desktopProcessResources") {
    exclude("*.icns", "*.ico")
}

// The screenshot test writes one PNG per size, theme and state here.
tasks.named<Test>("desktopTest") {
    val dir = layout.buildDirectory.dir("screenshots")
    outputs.dir(dir)
    systemProperty("screenshots.dir", dir.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    maxHeapSize = "2g"
}
