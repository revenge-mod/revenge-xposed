package io.github.revenge.xposed.tweaks.plugins.internal

import io.github.revenge.plugins.PluginBuilder
import io.github.revenge.plugins.PluginDependency
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.plugin
import io.github.revenge.xposed.tweaks.plugins.PluginFactory
import io.github.revenge.xposed.tweaks.plugins.repos.PluginSource

enum class InternalPluginFlags {
    INTERNAL,
    ESSENTIAL,
    ENABLED_BY_DEFAULT,
    API,
}

internal fun internalPlugin(
    manifest: PluginManifest,
    flags: Set<InternalPluginFlags> = setOf(InternalPluginFlags.INTERNAL),
    block: PluginBuilder.() -> Unit,
): PluginFactory {
    val builder = plugin(block)
    return PluginFactory(manifest.withReservedDependencies(), flags) { builder }
}

private fun internalPlugin(
    manifest: PluginManifest,
    flags: Set<InternalPluginFlags> = setOf(InternalPluginFlags.INTERNAL),
    defaultSource: PluginSource?,
    block: PluginBuilder.() -> Unit,
): PluginFactory {
    val builder = plugin(block)
    return PluginFactory(manifest.withReservedDependencies(), flags, defaultSource = defaultSource) { builder }
}

/** Registers a "stub" (to be updated) internal plugin. */
internal fun stubPlugin(
    manifest: PluginManifest,
    source: PluginSource,
    flags: Set<InternalPluginFlags> = setOf(InternalPluginFlags.INTERNAL),
    block: PluginBuilder.() -> Unit = {},
): PluginFactory {
    require(source.repo != null) { "Stub plugin ${manifest.id} needs a source repository" }
    require(manifest.id !in RESERVED_DEPENDENCY_IDS) { "Reserved plugin ${manifest.id} can't be a stub" }
    return internalPlugin(manifest, flags, source, block)
}

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

/** Internal plugin with [id], stubs included. */
internal fun internalPluginOf(id: String): PluginFactory? = internalPlugins.find { it.manifest.id == id }

/** Defaults sources to record, skipping plugins that already have provenance. */
internal fun defaultSourcesToSeed(
    internals: Collection<PluginFactory>,
    existing: Map<String, PluginSource>,
): Map<String, PluginSource> = buildMap {
    for (factory in internals) {
        val source = factory.defaultSource ?: continue
        if (factory.manifest.id !in existing) put(factory.manifest.id, source)
    }
}

/**
 * IDs of internal plugins replaced by an installed copy on disk.
 * **Only internals with provenance can be replaced.**
 */
internal fun shadowedInternalIds(
    internals: Collection<PluginFactory>,
    sources: Map<String, PluginSource>,
    isInstalled: (String) -> Boolean,
): Set<String> = internals.mapNotNullTo(mutableSetOf()) { factory ->
    factory.manifest.id.takeIf { it in sources && isInstalled(it) }
}
