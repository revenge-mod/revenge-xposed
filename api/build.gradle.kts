plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.revenge.api"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdkLibrary.get().toInt()

        buildConfigField("String", "API_VERSION", "\"${libs.versions.apiVersion.get()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        named("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
    }

    kotlin {
        jvmToolchain(libs.versions.javaVersion.get().toInt())
    }
}

dependencies {
    compileOnly(libs.xposed.api)
    compileOnly(libs.kotlinx.coroutines.android)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.github.revenge"
            artifactId = "api"
            version = libs.versions.apiVersion.get()

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
