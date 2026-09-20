package io.github.revenge.xposed.tweaks.plugins.internal

import io.github.revenge.logger
import io.github.revenge.plugins.PluginDependency
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange
import io.github.revenge.xposed.tweaks.BundleManifest
import io.github.revenge.xposed.tweaks.plugins.PluginFactory
import io.github.revenge.xposed.tweaks.plugins.external.requireValidPluginId

private val log = logger("bundledPlugins")


/**
 * Handles registration of internal JS-only (bundled) plugins. Registering them here makes the dependency graph complete.
 *
 * Native discovers external plugins and handles dependency resolution before JS starts, so without this,
 * it cannot tell an ID belonging to a bundled plugin from one that does not exist, which causes it to drop any
 * external plugin that depends on one.
 *
 * Even though nothing here is enabled by default, native registers them regardless, making them linkable by dependents.
 * JS later sets each plugin's default flags into the slot when its implementation registers.
 */
internal fun bundledPlugins(bundleManifest: BundleManifest): List<PluginFactory> {
    val bundleVersion = Version.parse(bundleManifest.version)

    return bundleManifest.plugins.mapNotNull { entry ->
        runCatching { internalPlugin(entry.toPluginManifest(bundleVersion), BUNDLED_PLUGIN_FLAGS) {} }
            .onFailure { log.e("Skipping bundled plugin '${entry.id}'", it) }
            .getOrNull()
    }
}

private val BUNDLED_PLUGIN_FLAGS = setOf(InternalPluginFlags.INTERNAL)

// JS will fill these in later.
private fun BundleManifest.Plugin.toPluginManifest(version: Version): PluginManifest {
    requireValidPluginId(id)

    return PluginManifest(
        id = id,
        name = id,
        description = "",
        author = "",
        dependencies = dependencies.entries.associate { (depId, dep) ->
            requireValidPluginId(depId)
            depId to PluginDependency(
                version = dep.version?.let(VersionRange::parse) ?: VersionRange.ANY,
                optional = dep.optional,
            )
        },
        version = version,
    )
}