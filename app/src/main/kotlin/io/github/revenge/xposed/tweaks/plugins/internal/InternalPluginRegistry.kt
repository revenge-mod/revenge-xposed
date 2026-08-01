package io.github.revenge.xposed.tweaks.plugins.internal

import io.github.revenge.plugins.PluginBuilder
import io.github.revenge.plugins.PluginDependency
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.plugin
import io.github.revenge.xposed.tweaks.plugins.InternalPluginFlags
import io.github.revenge.xposed.tweaks.plugins.PluginFactory

internal fun internalPlugin(
    manifest: PluginManifest,
    flags: Set<InternalPluginFlags> = emptySet(),
    block: PluginBuilder.() -> Unit
) = PluginFactory(plugin(block), manifest.withReservedDependencies(), flags)

/**
 * Injects the reserved dependencies ([RESERVED_DEPENDENCY_IDS]) at the ANY range,
 * to match requirements for all plugins (internal & external).
 */
private fun PluginManifest.withReservedDependencies(): PluginManifest {
    if (id in RESERVED_DEPENDENCY_IDS) return this
    val missing = RESERVED_DEPENDENCY_IDS - dependencies.keys
    if (missing.isEmpty()) return this
    return copy(dependencies = dependencies + missing.associateWith { PluginDependency() })
}

internal val internalPlugins: List<PluginFactory> by lazy {
    listOf(apiProviderPlugin, discordProviderPlugin, recoveryPlugin, noTrackPlugin, preventOtaUpdatesPlugin)
}