package io.github.revenge.xposed.plugins

/**
 * JS plugin dependency.
 */
data class PluginDependency(
    val id: String,
    /**
     * Optional suggested URL for the dependency.
     */
    val url: String? = null
)