plugins {
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("com.android.application") version "8.1.4" apply false
    kotlin("plugin.serialization") version "1.8.21" apply false
}
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
