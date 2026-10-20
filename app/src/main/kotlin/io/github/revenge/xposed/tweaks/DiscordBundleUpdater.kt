package io.github.revenge.xposed.tweaks

import io.github.revenge.xposed.DexFinder
import io.github.revenge.xposed.loadClassOrNull
import io.github.revenge.xposed.method
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class DiscordBundleUpdater(private val classLoader: ClassLoader) {
    private val legacyClass by lazy { classLoader.loadClassOrNull(LEGACY_CLASS) }

    /** The updater class. Discord creates one instance per process. */
    val updaterClass: Class<*>? by lazy {
        legacyClass ?: DexFinder.findClass("BundleUpdater") { findUpdaterClass() }
    }

    /** Returns the downloaded OTA bundle to run, or `null` to run the bundled one. */
    val getBundle: Method? by lazy {
        legacyClass?.let { runCatching { it.method("getBundle") }.getOrNull() }
            ?: DexFinder.findMethod("BundleUpdater.getBundle") { findGetBundle() }
    }

    /** Returns the commit of the downloaded OTA bundle, or `null` when running the bundled one. */
    val getOtaCommit: Method? by lazy {
        legacyClass?.let { runCatching { it.method("getExistingOtaCommit") }.getOrNull() }
            ?: DexFinder.findMethod("BundleUpdater.getExistingOtaCommit") {
                val getBundle = findGetBundle() ?: return@findMethod null
                findMethod {
                    matcher {
                        declaredClass(getBundle.className)
                        paramCount(0)
                        returnType("java.lang.String")
                        addInvoke(getBundle.descriptor)
                    }
                }.singleOrNull()
            }
    }

    /** Schedules an OTA update check, and returns its `Future`. */
    val checkForUpdate: Method? by lazy {
        legacyClass?.let {
            runCatching {
                // Discord bundles its own Kotlin, so its Function0 isn't ours.
                val function0 = classLoader.loadClass("kotlin.jvm.functions.Function0")
                it.method("checkForUpdate", Int::class.javaPrimitiveType, function0)
            }.getOrNull()
        } ?: DexFinder.findMethod("BundleUpdater.checkForUpdate") {
            val updater = findUpdaterClass() ?: return@findMethod null
            // R8 inlines the body into a shared Runnable, so the static `$default` overload is all that's left.
            findMethod {
                matcher {
                    declaredClass(updater.name)
                    modifiers(Modifier.STATIC)
                    returnType("java.util.concurrent.Future")
                }
            }.singleOrNull()
        }
    }

    private fun DexKitBridge.findUpdaterClass() = findClass {
        matcher { usingStrings(BUNDLE_PATH_KEY, "in_progress_ota_commit") }
    }.singleOrNull()

    private fun DexKitBridge.findGetBundle(): MethodData? {
        val updater = findUpdaterClass() ?: return null
        return findMethod {
            matcher {
                declaredClass(updater.name)
                paramCount(0)
                usingEqStrings(BUNDLE_PATH_KEY, "key_android_js_bundle_release_name")
            }
        }.singleOrNull()
    }

    private companion object {
        const val LEGACY_CLASS = "com.discord.bundle_updater.BundleUpdater"
        const val BUNDLE_PATH_KEY = "key_android_js_bundle"
    }
}
