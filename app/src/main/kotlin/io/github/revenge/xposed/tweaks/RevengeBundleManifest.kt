package io.github.revenge.xposed.tweaks

import android.content.res.XModuleResources
import io.github.revenge.Logger
import io.github.revenge.plugins.API_VERSION
import io.github.revenge.xposed.RevengeJson
import io.github.revenge.xposed.tweak
import kotlinx.serialization.Serializable

private const val MODULE_MANIFEST_FILE = "manifest.json"
private lateinit var logger: Logger

const val BUNDLE_MANIFEST_FORMAT = 1

@Serializable
data class BundleManifest(
    val format: Int = BUNDLE_MANIFEST_FORMAT,
    val version: String,
    val plugins: List<Plugin> = emptyList(),
) {
    @Serializable
    data class Plugin(
        val id: String,
        /** Cannot be turned off. Implies [enabledByDefault]. */
        val essential: Boolean = false,
        /** On until the user says otherwise. */
        val enabledByDefault: Boolean = false,
        val dependencies: Map<String, PluginDependency> = emptyMap(),
    )

    @Serializable
    data class PluginDependency(
        val version: String? = null,
        val optional: Boolean = false,
    )
}

lateinit var bundleManifest: BundleManifest

val revengeBundleManifest by tweak {
    logger = log

    runCatching { RevengeUpdater.readBundleManifest() }
        .onFailure { log.e("Unable to read downloaded bundle manifest", it) }
        .getOrNull()?.let {
            if (setBundleManifest(it)) {
                log.i("Remote bundle manifest: $bundleManifest")
                return@tweak
            }
        }

    log.i("Using module bundle manifest...")
    runCatching {
        XModuleResources.createInstance(modulePath, null)
            .assets.open(MODULE_MANIFEST_FILE)
            .use { it.readBytes().decodeToString() }
    }
        .onFailure { log.w("No bundle manifest in module assets", it) }
        .getOrNull()?.let {
            if (setBundleManifest(it)) return@tweak
        }

    log.w("Using fallback manifest...")
    bundleManifest = BundleManifest(BUNDLE_MANIFEST_FORMAT, API_VERSION.toString(), emptyList())
}

private fun setBundleManifest(json: String): Boolean {
    bundleManifest = parseBundleManifest(json) ?: return false

    return true
}

fun parseBundleManifest(json: String) =
    runCatching { RevengeJson.decodeFromString<BundleManifest>(json) }
        .onFailure { logger.e("Unable to parse bundle manifest", it) }
        .getOrNull()
