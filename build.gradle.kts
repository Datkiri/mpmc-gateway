import org.gradle.api.tasks.bundling.Zip

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = "org.mpmc"
version = "0.1.0"

subprojects {
    group = "org.mpmc"
    version = "0.1.0"
}

val packageAgent by tasks.registering(Zip::class) {
    dependsOn(":agent-cli:installDist")
    archiveBaseName.set("mpmc-agent")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    from(project(":agent-cli").layout.buildDirectory.dir("install/mpmc")) { into("mpmc") }
    from("packaging") {
        include(
            "run-host.sh", "run-join.sh", "run-host.bat", "run-join.bat",
            "mpmc-join.service", "join.env.example", "config.example.toml",
        )
        into("mpmc")
    }
    from("docs") {
        include(
            "RUNBOOK.md", "M1-COORDINATOR.md", "M2-TUNNEL.md", "M3-AUTH.md", "M4-RELAY.md",
            "M5-REPORT.md", "M6-BEDROCK.md", "M6-PACKET-INVENTORY.md", "MEASUREMENTS.md",
        )
        into("mpmc/docs")
    }
    from("README.md") { into("mpmc") }
}
