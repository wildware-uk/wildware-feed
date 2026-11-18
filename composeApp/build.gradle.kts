import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)

    // Mac, Windows and Linux all run this one JVM build.
    jvm("desktop")

    // The iPhone app will embed this framework and show the Compose screen.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
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
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(compose.desktop.currentOs)
                implementation(libs.compose.ui.test)
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

// The screenshot test writes one PNG per size, theme and state here.
tasks.named<Test>("desktopTest") {
    val dir = layout.buildDirectory.dir("screenshots")
    outputs.dir(dir)
    systemProperty("screenshots.dir", dir.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    maxHeapSize = "2g"
}
