package io.github.revenge.xposed.tweaks.plugins.internal

import io.github.revenge.plugins.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every internal plugin gains `revenge.api` and `discord` at the ANY range. The providers themselves are exempt.
 */
class ReservedDependencyInjectionTest {
    init {
        // Assigned at runtime by DiscordVersionRetriever, so tests have to provide a value themselves.
        DISCORD_VERSION = Version.parse("0")
    }

    private fun manifest(
        id: String,
        dependencies: Map<String, PluginDependency> = emptyMap(),
    ) = PluginManifest(
        id = id,
        name = "Test",
        description = "",
        author = "",
        dependencies = dependencies,
        version = Version.parse("1.0.0"),
    )

    @Test
    fun `internal plugins gain both reserved dependencies at the ANY range`() {
        val factory = internalPlugin(manifest("test.plugin")) {}

        val deps = factory.manifest.dependencies
        assertEquals(RESERVED_DEPENDENCY_IDS, deps.keys)
        assertTrue(deps.values.all { it.version == VersionRange.ANY && !it.optional })
    }

    @Test
    fun `explicitly declared reserved dependencies are not overwritten`() {
        val declared = PluginDependency(version = VersionRange.parse(">=1"))
        val factory = internalPlugin(
            manifest("test.plugin", dependencies = mapOf(API_DEPENDENCY_ID to declared)),
        ) {}

        val deps = factory.manifest.dependencies
        assertEquals(declared, deps[API_DEPENDENCY_ID])
        assertEquals(PluginDependency(), deps[DISCORD_DEPENDENCY_ID])
    }

    @Test
    fun `providers are exempt from injection`() {
        assertTrue(apiProviderPlugin.manifest.dependencies.isEmpty())
        assertTrue(discordProviderPlugin.manifest.dependencies.isEmpty())
    }

    @Test
    fun `providers carry the reserved ids and essential internal flags`() {
        assertEquals(API_DEPENDENCY_ID, apiProviderPlugin.manifest.id)
        assertEquals(DISCORD_DEPENDENCY_ID, discordProviderPlugin.manifest.id)
        for (provider in listOf(apiProviderPlugin, discordProviderPlugin)) {
            assertTrue(io.github.revenge.xposed.tweaks.plugins.InternalPluginFlags.INTERNAL in provider.internalFlags)
            assertTrue(io.github.revenge.xposed.tweaks.plugins.InternalPluginFlags.ESSENTIAL in provider.internalFlags)
        }
    }
}
