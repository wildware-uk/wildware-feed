import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.serialization.json)
    testImplementation(libs.coroutines.core)
    testImplementation(kotlin("test"))
    // Read-only look at the server's SQLite file, for the few checks only the schema can answer.
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Every PARITY_* variable reaches the tests; nothing else about the box should matter.
    System.getenv().filterKeys { it.startsWith("PARITY_") }.forEach { (k, v) -> environment(k, v) }
    // The suite shares one server, and some checks (rate limits, the event stream) assume
    // nobody else is writing at the same moment.
    maxParallelForks = 1
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // A hung stream must fail the run, not hold CI.
    timeout.set(Duration.ofMinutes(20))
    // Always run: the server under test changes without Gradle knowing.
    outputs.upToDateWhen { false }
}
