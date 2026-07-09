pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://api.xposed.info/")
    }
}

rootProject.name = "RevengeXposed"
include(":app")
include(":api")
