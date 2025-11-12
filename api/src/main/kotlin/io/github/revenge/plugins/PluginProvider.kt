package io.github.revenge.plugins

interface PluginProvider {
    /**
     * The manifest information about the plugin.
     */
    val manifest: PluginManifest

    /**
     * Creates an instance of the plugin.
     */
    fun createPlugin(): Plugin
}