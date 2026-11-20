import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

/** The version, from `agents.version` in gradle.properties. */
val agentsVersion = providers.gradleProperty("agents.version").get()

/** Writes the version into the desktop app's code, so the updater knows what it is running. */
val generateAppVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/appVersion/kotlin")
    val version = agentsVersion
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val file = out.get().file("dev/wildware/agents/app/update/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package dev.wildware.agents.app.update
            |
            |/** The version this build was made as: `agents.version` in gradle.properties. Generated; do not edit. */
            |const val AppVersion: String = "$version"
            |""".trimMargin(),
        )
    }
}

/**
 * Where crash reports go, written into the app's code. Release CI sets SENTRY_DSN_APP from a secret;
 * any other build (a self-hoster's, a local one) has none, and sends nothing. SENTRY_ENVIRONMENT says
 * production or debug; SENTRY_PROGUARD_UUID names the Android build's R8 mapping, which CI uploads.
 */
val generateCrashReportingBuild by tasks.registering {
    val out = layout.buildDirectory.dir("generated/crashReporting/kotlin")
    val dsn = providers.environmentVariable("SENTRY_DSN_APP").orElse("")
    val environment = providers.environmentVariable("SENTRY_ENVIRONMENT").orElse("debug")
    val proguardUuid = providers.environmentVariable("SENTRY_PROGUARD_UUID").orElse("")
    val version = agentsVersion
    inputs.property("dsn", dsn)
    inputs.property("environment", environment)
    inputs.property("proguardUuid", proguardUuid)
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        val file = out.get().file("dev/wildware/agents/app/crash/CrashReportingBuild.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package dev.wildware.agents.app.crash
            |
            |/** How this build reports crashes. Generated from the build's environment; do not edit. */
            |internal object CrashReportingBuild {
            |    /** Where reports go. Empty: this build sends none, and offers no switch for it. */
            |    const val Dsn: String = ${quoted(dsn.get())}
            |    const val Environment: String = ${quoted(environment.get())}
            |    const val Release: String = ${quoted("wildware-feed@$version")}
            |    /** The Android R8 mapping's id, or empty. */
            |    const val ProguardUuid: String = ${quoted(proguardUuid.get())}
            |}
            |""".trimMargin(),
        )
    }
}

/**
 * The hosted "Log in"'s account service (docs/HOSTING.md §5). `-Pfeed.accountUrl=…` or
 * FEED_ACCOUNT_URL points a build at another one (a staging service, a fake on this computer);
 * set to an empty string, the build offers no "Log in" at all.
 */
val generateHostedLoginBuild by tasks.registering {
    val out = layout.buildDirectory.dir("generated/hostedLogin/kotlin")
    val url = providers.gradleProperty("feed.accountUrl")
        .orElse(providers.environmentVariable("FEED_ACCOUNT_URL"))
        .orElse("https://account.feed.wildware.dev")
    inputs.property("url", url)
    outputs.dir(out)
    doLast {
        val value = url.get().trim().trimEnd('/')
        require(value.isEmpty() || value.startsWith("https://") || value.startsWith("http://localhost") || value.startsWith("http://127.0.0.1")) {
            "feed.accountUrl must be https:// (or http://localhost for a test): $value"
        }
        val file = out.get().file("dev/wildware/agents/app/signin/HostedLoginBuild.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package dev.wildware.agents.app.signin
            |
            |/** Where this build's "Log in" goes. Generated from `feed.accountUrl`; do not edit. Empty: no "Log in". */
            |internal object HostedLoginBuild {
            |    const val AccountService: String = "${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")}"
            |}
            |""".trimMargin(),
        )
    }
}

kotlin {
    jvmToolchain(21)

    // Mac, Windows and Linux all run this one JVM build.
    jvm("desktop")

    // Android: the screens and the platform hooks. The app itself (manifest, activity, icons) is androidApp/.
    android {
        namespace = "dev.wildware.agents.app"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // The fonts are Compose resources, which travel in the library's assets.
        androidResources { enable = true }
    }

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

    // The web app: the same screens in a browser, served by the server itself at its root.
    // `wasmJsBrowserDistribution` builds it into build/dist/wasmJs/productionExecutable.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "feed"
        browser {
            commonWebpackConfig { outputFileName = "feed.js" }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateCrashReportingBuild)
            kotlin.srcDir(generateHostedLoginBuild)
        }
        commonMain.dependencies {
            implementation(project(":shared"))
            // Crash reports: the Java SDK on desktop, Android's on Android, Cocoa's on the iPhone. Never
            // session replay, and no native (NDK) handler: the Android app has no native code of its own.
            implementation(libs.sentry.kmp.get().let { "${it.module}:${it.versionConstraint.requiredVersion}" }) {
                exclude(group = "io.sentry", module = "sentry-android-replay")
                exclude(group = "io.sentry", module = "sentry-android-ndk")
            }
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            // Back handlers on the platform's own back input: UIKit's screen-edge pan on the iPhone.
            implementation(libs.navigationevent.compose)
            implementation(libs.compose.resources)
            implementation(libs.markdown.renderer.m3)
            implementation(libs.markdown.renderer.code)
            implementation(libs.highlights)
            implementation(libs.ktor.client.core)
            // "Try a demo" answers the app from memory through ktor's mock network.
            implementation(libs.ktor.client.mock)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.datetime)
        }
        val desktopMain by getting {
            // The crash report setup the desktop and Android share: both run Sentry's Java SDK.
            kotlin.srcDir("src/jvmShared/kotlin")
            // The window icon is the installers' own picture, so the two never drift apart.
            resources.srcDir("packaging/icons")
            kotlin.srcDir(generateAppVersion)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.jna)
                // "This computer": the app can run the Wildware Feed server itself.
                implementation(project(":server"))
                // Plays videos and sounds in the app (AVFoundation, Media Foundation, GStreamer). It lists
                // the coroutines test kit as a runtime dependency, which has no place in the app.
                implementation(libs.composemediaplayer.get().let { "${it.module}:${it.versionConstraint.requiredVersion}" }) {
                    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test-jvm")
                    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test")
                }
            }
        }
        androidMain {
            kotlin.srcDir("src/jvmShared/kotlin")
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
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
                implementation(libs.coroutines.test)
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
            // AppImage here is jpackage's plain app folder; CI packs it into a real .AppImage.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "Wildware Feed"
            packageVersion = agentsVersion
            description = "Wildware Feed: questions and updates from your agents"
            vendor = "Wildware"
            copyright = "© 2026 Wildware"
            // What `suggestRuntimeModules` found, plus the TLS curves OkHttp needs that it can't see.
            // java.sql and java.net.http are for the server the app can host ("This computer").
            // jdk.httpserver: the loopback proxy the video player streams through (LoopbackMediaProxy).
            modules("java.instrument", "java.management", "jdk.unsupported", "java.naming", "jdk.crypto.ec", "java.sql", "java.net.http", "jdk.httpserver")
            macOS {
                bundleID = "dev.wildware.agents.desktop"
                dockName = "Wildware Feed"
                appCategory = "public.app-category.productivity"
                iconFile.set(project.file("packaging/icons/agents.icns"))
                // agents:// links, as the phones take them: an invite's page opens the join screen.
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>dev.wildware.agents.desktop</string>
                                <key>CFBundleURLSchemes</key>
                                <array><string>agents</string></array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }
            windows {
                iconFile.set(project.file("packaging/icons/agents.ico"))
                menuGroup = "Wildware Feed"
                shortcut = true
                perUserInstall = true
                // Fixed for good: it is how Windows knows a new .msi upgrades the old one.
                upgradeUuid = "6a1d6f3e-4f0b-4c39-9d2e-2b7c1a7e5d41"
            }
            linux {
                iconFile.set(project.file("packaging/icons/agents.png"))
                // The Debian/RPM package keeps its old name, so a new one upgrades the installed "agents" in place.
                packageName = "agents"
                debMaintainer = "noreply@wildware.dev"
                menuGroup = "Network"
                shortcut = true
                appCategory = "Network"
            }
        }
    }
}

// Only the PNG is needed at run time; the .icns and .ico are for the installers.
tasks.named<ProcessResources>("desktopProcessResources") {
    exclude("*.icns", "*.ico")
}

// The web page's icon is the installers' picture too.
tasks.named<ProcessResources>("wasmJsProcessResources") {
    from("packaging/icons/agents.png") { rename { "icon.png" } }
}

// The screenshot test writes one PNG per size, theme and state here.
tasks.named<Test>("desktopTest") {
    val dir = layout.buildDirectory.dir("screenshots")
    outputs.dir(dir)
    systemProperty("screenshots.dir", dir.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    maxHeapSize = "2g"
}
