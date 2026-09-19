package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.plugins.Plugin
import io.github.revenge.plugins.PluginBuilder
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange
import io.github.revenge.xposed.tweaks.plugins.external.DiscoveryFailure
import io.github.revenge.xposed.tweaks.plugins.external.forgetNativePluginLoader
import io.github.revenge.xposed.tweaks.plugins.internal.InternalPluginFlags

/** [PluginManifest] + [load] method to get [PluginBuilder] + internal flags for registration. */
internal class PluginFactory(
    val manifest: PluginManifest,
    val internalFlags: Set<InternalPluginFlags> = emptySet(),
    /** Absolute path to the plugin's `dist.script` JS bundle. */
    val scriptPath: String? = null,
    /** Loads the plugin's code, chaining the class loaders of the given dependencies. */
    private val load: (chain: Set<String>) -> PluginBuilder,
) {
    /** The plugin's Dependencies class loader chain, or `null` until it is first built. */
    var chainedDependencies: Set<String>? = null
        private set

    private var builder: PluginBuilder? = null

    /** Builds the plugin with [chain], relinking its code only if chain chained since the last build. */
    fun build(chain: Set<String>): Plugin {
        val current = builder?.takeIf { chainedDependencies == chain }
            ?: load(chain).also {
                builder = it
                chainedDependencies = chain
            }

        return current.build(manifest)
    }
}

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

    /** Manifests of loaded plugins. */
    fun installedManifests(): Map<String, PluginManifest> =
        factories.values.associate { it.manifest.id to it.manifest }

    private var graphsStale = true
    private var cachedDependencies: PluginDependencyGraph? = null
    private var cachedKnownDependencies: PluginDependencyGraph? = null

    /** Dependency graph of loaded plugins. */
    val dependencies: PluginDependencyGraph
        get() = rebuildGraphsIfStale().let { cachedDependencies!! }

    /** Dependency graph of every plugin with a valid manifest, loaded or not. */
    val knownDependencies: PluginDependencyGraph
        get() = rebuildGraphsIfStale().let { cachedKnownDependencies!! }

    private fun rebuildGraphsIfStale() {
        if (!graphsStale && cachedDependencies != null) return
        cachedDependencies = PluginDependencyGraph(installedManifests())
        cachedKnownDependencies = PluginDependencyGraph(knownManifests())
        graphsStale = false
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
        graphsStale = true
    }

    /** Forgets in-memory trace of a plugin, including its cached DEX loader. */
    fun forget(id: String) {
        factories.remove(id)
        discoveryFailures.remove(id)
        pendingUpdates.remove(id)
        bootErrors.remove(id)
        forgetNativePluginLoader(id)
        graphsStale = true
    }

    fun recordDiscoveryFailures(failures: Map<String, DiscoveryFailure>) {
        discoveryFailures.putAll(failures)
        // Discovery failures are graph nodes too.
        graphsStale = true
    }
}
