package io.github.revenge.xposed.plugins

/**
 * Information about a plugin.
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val icon: String? = null,
    val dependencies: ArrayList<PluginDependency> = arrayListOf()
)
