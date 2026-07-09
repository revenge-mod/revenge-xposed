package io.github.revenge.xposed.tweaks.plugins.internal

import io.github.revenge.plugins.PluginBuilder
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.plugin
import io.github.revenge.xposed.tweaks.plugins.InternalPluginFlags
import io.github.revenge.xposed.tweaks.plugins.PluginFactory

internal fun internalPlugin(
    manifest: PluginManifest,
    flags: Set<InternalPluginFlags> = emptySet(),
    block: PluginBuilder.() -> Unit
) = PluginFactory(plugin(block), manifest, flags)

internal val internalPlugins: List<PluginFactory> by lazy {
    listOf(noTrackPlugin)
}