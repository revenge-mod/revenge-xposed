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
            kotlin.directories += "src/main/kotlin"
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

// Forks publish to their own repository's packages.
val githubRepository = providers.environmentVariable("GITHUB_REPOSITORY").getOrElse("revenge-mod/revenge-xposed")

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.github.revenge"
            artifactId = "api"
            version = libs.versions.apiVersion.get()

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name = "Revenge API"
                description = "API for building native Revenge plugins"
                url = "https://github.com/$githubRepository"

                licenses {
                    license {
                        name = "GNU General Public License v3.0"
                        url = "https://www.gnu.org/licenses/gpl-3.0.html"
                    }
                }

                scm {
                    url = "https://github.com/$githubRepository"
                    connection = "scm:git:https://github.com/$githubRepository.git"
                }
            }
        }
    }

    repositories {
        // Credentials come from the `GitHubPackagesUsername` and `GitHubPackagesPassword` Gradle properties.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/$githubRepository")
            credentials(PasswordCredentials::class)
        }
    }
}
