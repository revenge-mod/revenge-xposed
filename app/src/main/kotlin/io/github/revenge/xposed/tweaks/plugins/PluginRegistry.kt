package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.plugins.PluginBuilder
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange
import io.github.revenge.xposed.tweaks.plugins.external.DiscoveryFailure
import io.github.revenge.xposed.tweaks.plugins.external.forgetNativePluginLoader
import io.github.revenge.xposed.tweaks.plugins.internal.InternalPluginFlags

/** [PluginBuilder] + [PluginManifest] + internal flags for registration. */
internal class PluginFactory(
    val builder: PluginBuilder,
    val manifest: PluginManifest,
    val internalFlags: Set<InternalPluginFlags> = emptySet(),
    /** Absolute path to the plugin's `dist.script` JS bundle. */
    val scriptPath: String? = null,
    /** Optional dependencies that are installed but unsatisfied, so this plugin loaded without linking them. */
    val unsatisfiedOptionalDependencies: Set<String> = emptySet(),
)

// class instead of object because tests
internal class PluginRegistry {
    val factories = mutableMapOf<String, PluginFactory>()

    /** Plugins that failed discovery this session. Not immediately disabled, but will skip running this session. */
    val discoveryFailures = mutableMapOf<String, DiscoveryFailure>()

    /** Boot-time load failures per plugin. */
    val bootErrors = mutableMapOf<String, PluginError>()

    /** Plugins that are running by ID. */
    val loaded = mutableMapOf<String, LoadedPlugin>()

    /**
     * Updates are applied on-disk only. The running session keeps running with what already loaded.
     * Updated plugins will load on next boot.
     */
    val pendingUpdates = mutableMapOf<String, Version>()

    /** Manifests of every known plugin (factories + failures with a valid manifest). */
    fun knownManifests(): Map<String, PluginManifest> = buildMap {
        for ((id, failure) in discoveryFailures) failure.manifest?.let { put(id, it) }
        for (factory in factories.values) put(factory.manifest.id, factory.manifest)
    }

    fun installedVersions(): Map<String, Version> = factories.values.associate { it.manifest.id to it.manifest.version }

    fun installedDependencies(): Map<String, Map<String, VersionRange>> =
        factories.values.associate { it.manifest.id to it.manifest.dependencies.mapValues { ent -> ent.value.version } }

    /** Registers a factory, dropping stale failures and errors for the ID. */
    fun add(factory: PluginFactory) {
        val id = factory.manifest.id
        factories[id] = factory
        discoveryFailures.remove(id)
        bootErrors.remove(id)
    }

    /** Forgets in-memory trace of a plugin, including its cached DEX loader. */
    fun forget(id: String) {
        factories.remove(id)
        discoveryFailures.remove(id)
        pendingUpdates.remove(id)
        bootErrors.remove(id)
        forgetNativePluginLoader(id)
    }
}
