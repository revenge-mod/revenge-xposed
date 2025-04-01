pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://api.xposed.info/")
    }
}

include(":plugin")
include(":app")

rootProject.name = "RevengeXposed"
