plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    testImplementation(project(":protocol"))
    testImplementation(project(":transport-iroh"))
    testImplementation(project(":agent-core"))
    testImplementation(project(":agent-cli"))
    testImplementation(project(":coordinator"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.slf4j.api)
    testImplementation(libs.logback.classic)
    testImplementation(libs.hikaricp)
    testImplementation(libs.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.zonky.embedded.postgres)

    if (System.getProperty("os.name").lowercase().contains("windows")) {
        testRuntimeOnly(libs.zonky.binaries.windows)
    } else {
        testRuntimeOnly(libs.zonky.binaries.linux)
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    maxHeapSize = "1g"
}
