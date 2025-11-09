package io.github.revenge.xposed.plugins.internal

import io.github.revenge.xposed.modules.plugins.InternalPluginFlags
import io.github.revenge.xposed.plugins.PluginProvider

internal abstract class InternalPluginProvider(val internalFlags: Set<InternalPluginFlags>) : PluginProvider {
    fun isEssential(): Boolean {
        return internalFlags.contains(InternalPluginFlags.ESSENTIAL)
    }
}