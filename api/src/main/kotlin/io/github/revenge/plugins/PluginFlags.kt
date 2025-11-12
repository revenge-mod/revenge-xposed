package io.github.revenge.plugins

enum class PluginFlags(val value: Int) {
    /**
     * Indicates that the plugin is enabled.
     */
    ENABLED(1 shl 0),

    /**
     * Indicates that the plugin requires a reload to apply changes.
     */
    RELOAD_REQUIRED(1 shl 1),

    /**
     * Indicates that the plugin has encountered an error.
     */
    ERRORED(1 shl 2),

    /**
     * Indicates that this plugin was enabled after the initial load. For example, by the user after the app has already started.
     *
     * Plugins that do early tasks can use this flag to check if they missed their chance to run those tasks and add [RELOAD_REQUIRED] to their [Plugin.flags] if necessary.
     */
    ENABLED_LATE(1 shl 3),
}