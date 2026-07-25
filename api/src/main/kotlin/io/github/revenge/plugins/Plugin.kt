package io.github.revenge.plugins

import io.github.revenge.api.BuildConfig

/**
 * A Revenge plugin. Use the [plugin] builder to create a plugin.
 *
 * Plugins are loaded once, then [start] runs with [PluginScope].
 * If you need access to [android.content.Context], capture it with `ctx.withAppContext { ... }` inside [start].
 * Override only what you need; both lifecycle hooks are no-ops by default.
 */
abstract class Plugin internal constructor(val manifest: PluginManifest) {
    /** Called once when the plugin is loaded. Register bridge methods + install hooks here. */
    open fun start(ctx: PluginScope) {}

    /** Called when the plugin is being torn down. */
    open fun stop(ctx: PluginScope) {}
}

data class PluginManifest(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val icon: String? = null,
    /** Dependencies keyed by plugin ID. */
    val dependencies: Map<String, PluginDependency> = emptyMap(),
    val version: Version,
)

/**
 * A plugin dependency specification.
 * The dependency's plugin ID is the key in [PluginManifest.dependencies].
 */
data class PluginDependency(
    /**
     * Version range the dependency must satisfy.
     * 
     * Defaults to [VersionRange.ANY] (any version satisfies the dependency).
     */
    val version: VersionRange = VersionRange.ANY,
    /**
     * When `true`, this dependency never blocks the dependent when missing, version-unsatisfied, or failed to load.
     *
     * When available, the dependency is ordered first and its class loader is chained,
     * so availability is detectable via `Class.forName(name, false, javaClass.classLoader)`.
     */
    val optional: Boolean = false,
)

/**
 * The current Revenge plugin API version.
 */
val API_VERSION: Version = Version.parse(BuildConfig.API_VERSION)

/**
 * The reserved dependency ID resolving to the [API_VERSION].
 *
 * External plugins **MUST** declare a dependency on this ID. Plugins that don't won't be loaded.
 */
const val API_DEPENDENCY_ID: String = "revenge.api"

/**
 * The reserved dependency ID resolving to the host Discord app's version.
 *
 * The version is determined at runtime based on the app the module is loaded into,
 * so it lives in the loader, not here. This library only defines the contract.
 *
 * External plugins **MUST** declare a dependency on this ID. Plugins that don't won't be loaded.
 */
const val DISCORD_DEPENDENCY_ID: String = "discord"
