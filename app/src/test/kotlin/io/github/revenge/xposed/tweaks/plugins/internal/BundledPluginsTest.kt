package io.github.revenge.xposed.tweaks.plugins.internal

import io.github.revenge.plugins.Version
import io.github.revenge.xposed.tweaks.parseBundleManifest
import io.github.revenge.xposed.tweaks.plugins.PluginFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class BundledPluginsTest {
    private fun parseBundledPlugins(json: String): List<PluginFactory> {
        val manifest = parseBundleManifest(json) ?: error("Failed to parse bundle manifest")
        return bundledPlugins(manifest)
    }

    @Test
    fun `reads ids, dependencies and the bundle version`() {
        val plugins = parseBundledPlugins(
            """
            {
              "version": "1.2.3-rc1",
              "plugins": [
                { "id": "revenge.settings" },
                {
                  "id": "revenge.settings.plugins",
                  "dependencies": { "revenge.settings": { "version": ">=1.0" } }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("revenge.settings", "revenge.settings.plugins"), plugins.map { it.manifest.id })

        val settings = plugins[0].manifest
        assertEquals(Version.parse("1.2.3-rc1"), settings.version)
        // Every plugin carries the reserved dependencies, exactly as a native internal plugin does.
        assertEquals(RESERVED_DEPENDENCY_IDS, settings.dependencies.keys)

        val dep = plugins[1].manifest.dependencies.getValue("revenge.settings")
        assertEquals(">=1.0", dep.version.toString())
        assertEquals(false, dep.optional)
    }

    @Test
    fun `builds an empty plugin, since the implementation is in the bundle`() {
        val plugins = parseBundledPlugins("""{"version":"1.0.0","plugins":[{"id":"revenge.settings"}]}""")
        val factory = plugins.single()

        assertEquals(
            setOf(InternalPluginFlags.INTERNAL),
            factory.internalFlags,
        )
    }

    @Test
    fun `enablement from the manifest`() {
        val plugins = parseBundledPlugins(
            """
            {
              "version": "1.0.0",
              "plugins": [
                { "id": "revenge.settings", "essential": true },
                { "id": "revenge.no-track", "enabledByDefault": true },
                { "id": "revenge.api.hidden" }
              ]
            }
            """.trimIndent()
        )

        val flags = plugins.associate { it.manifest.id to it.internalFlags }

        assertEquals(
            setOf(InternalPluginFlags.INTERNAL, InternalPluginFlags.ESSENTIAL),
            flags.getValue("revenge.settings"),
        )
        assertEquals(
            setOf(InternalPluginFlags.INTERNAL, InternalPluginFlags.ENABLED_BY_DEFAULT),
            flags.getValue("revenge.no-track"),
        )
        // Absent means off: a plugin the build resolved as dev-only-by-default in a release bundle.
        assertEquals(setOf(InternalPluginFlags.INTERNAL), flags.getValue("revenge.api.hidden"))
    }

    @Test
    fun `a malformed entry does not take the rest of the set down`() {
        val plugins = parseBundledPlugins(
            """
            {
              "version": "1.0.0",
              "plugins": [
                { "id": "../escape" },
                { "id": "revenge.settings" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("revenge.settings"), plugins.map { it.manifest.id })
    }
}
