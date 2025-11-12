package io.github.revenge.plugins

/**
 * Read-only, safe view of a plugin exposed to external plugin developers.
 * This prevents third-party plugins from receiving the full mutable Plugin instance.
 */
interface PluginView {
    val manifest: PluginManifest
    val flags: Set<PluginFlags>
}

