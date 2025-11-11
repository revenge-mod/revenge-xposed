package io.github.revenge.xposed.plugins

import io.github.revenge.plugins.PluginProvider
import io.github.revenge.xposed.modules.plugins.InternalPluginFlags

internal abstract class InternalPluginProvider(val internalFlags: Set<InternalPluginFlags>) : PluginProvider {
    fun isEssential(): Boolean {
        return internalFlags.contains(InternalPluginFlags.ESSENTIAL)
    }

    fun isEnabledByDefault(): Boolean {
        return internalFlags.contains(InternalPluginFlags.ENABLED_BY_DEFAULT)
    }
}