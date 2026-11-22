import java.io.File
import java.time.Duration
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    // `server` runs the HTTP server; `server <command>` runs the admin CLI (Main.kt).
    mainClass.set("dev.wildware.feed.server.MainKt")
    applicationName = "feed-server"
    // sqlite-jdbc loads its native library; say so, rather than print a warning on every start.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.postgresql)
    implementation(libs.bouncycastle)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    // Crash reports, only when SENTRY_DSN is set (CrashReports.kt).
    implementation(libs.sentry.java)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(18))
    // Every statement the server runs on a team's table must name team_id (db/TeamTables.kt).
    systemProperty("feed.sqlLint", "true")
}

/** The release version (`agents.version` in gradle.properties), for `GET /api/server`. */
val generateServerVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/serverVersion")
    val version = providers.gradleProperty("agents.version").get()
    inputs.property("version", version)
    outputs.dir(out)
    doLast { out.get().file("feed-server-version.txt").asFile.apply { parentFile.mkdirs() }.writeText(version) }
}
sourceSets.main { resources.srcDir(generateServerVersion) }


/*
 * The web app: composeApp's browser build, installed as `web/` beside `lib/`, where the server
 * finds it and serves it at the site's root (WebApp.kt). Each big file gets a gzip copy beside it,
 * sent to browsers that take gzip. `-Pfeed.web=false` leaves it out (the parity run has no use for it).
 */
val withWeb = providers.gradleProperty("feed.web").map { it != "false" }.getOrElse(true)

val webBundle by tasks.registering(Sync::class) {
    dependsOn(":composeApp:wasmJsBrowserDistribution")
    from(rootProject.layout.projectDirectory.dir("composeApp/build/dist/wasmJs/productionExecutable"))
    // Source maps are for debugging the build, not for the owner's browser.
    exclude("**/*.map")
    into(layout.buildDirectory.dir("web"))
    doLast {
        destinationDir.walkTopDown()
            .filter { it.isFile && it.extension in setOf("wasm", "js", "html", "ttf", "json") && it.length() > 1024 }
            .forEach { file ->
                File(file.path + ".gz").outputStream().use { out ->
                    object : GZIPOutputStream(out) {
                        init { def.setLevel(Deflater.BEST_COMPRESSION) }
                    }.use { gz -> file.inputStream().use { it.copyTo(gz) } }
                }
            }
    }
}

if (withWeb) {
    distributions.named("main") {
        contents { from(webBundle) { into("web") } }
    }
}
