pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "mpmc"

include(":protocol")
include(":transport-iroh")
include(":agent-core")
include(":agent-cli")
include(":coordinator")
include(":it")
