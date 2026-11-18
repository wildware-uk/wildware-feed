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
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.sqlite.jdbc)
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
