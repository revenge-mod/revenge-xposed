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
    val dependencies: ArrayList<PluginDependency> = arrayListOf(),
)

/**
 * JS plugin dependency.
 *
 * Native plugin dependencies can link against other plugins during compile-time instead.
 */
data class PluginDependency(
    val id: String,
    /**
     * Optional suggested URL for the dependency.
     */
    val url: String? = null,
)

/**
 * Minimal [semantic version](https://semver.org) (`major.minor.patch`), comparable by precedence.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /** Parse `"X.Y.Z"` (missing parts default to `0`); pre-release/build suffixes are dropped. */
        fun parse(value: String): SemVer {
            val core = value.trim().substringBefore('-').substringBefore('+')
            val parts = core.split('.')
            fun part(index: Int) = parts.getOrNull(index)?.toIntOrNull() ?: 0
            return SemVer(part(0), part(1), part(2))
        }
    }
}

/**
 * The current Revenge plugin API version.
 *
 * An external plugin records the API version it was linked against in its manifest (`api_version`).
 * The loader compares that against this value to decide whether the plugin is compatible.
 */
val API_VERSION: SemVer = SemVer.parse(BuildConfig.API_VERSION)
