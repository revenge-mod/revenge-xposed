plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    kotlin("plugin.serialization")
}

android {
    namespace = "io.github.revenge.xposed"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.revenge.xposed"
        minSdk = 24
        targetSdk = 35
        versionCode = 1202
        versionName = version.toString()
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        // TODO: Align Xposed native libraries to 16KB.
        disable += "Aligned16KB"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("com.facebook.react:react-android:0.71.8")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("io.ktor:ktor-client-core:3.1.1")
    implementation("io.ktor:ktor-client-cio:3.1.1")
}
