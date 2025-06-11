plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.github.revenge"
    compileSdk = 35
    
    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    compileOnly(project(":plugin"))
}