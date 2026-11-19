import java.time.Duration

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
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(10))
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

