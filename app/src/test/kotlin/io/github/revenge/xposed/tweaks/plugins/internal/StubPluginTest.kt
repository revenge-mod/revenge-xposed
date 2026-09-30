package io.github.revenge.xposed.tweaks.plugins.internal

import io.github.revenge.plugins.API_DEPENDENCY_ID
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.Version
import io.github.revenge.xposed.tweaks.plugins.repos.PluginSource
import kotlin.test.*

class StubPluginTest {
    private val repo = "https://example.com/index.json"

    private fun manifest(id: String) = PluginManifest(
        id = id,
        name = "Test",
        description = "",
        author = "",
        version = Version.parse("1.0.0"),
    )

    @Test
    fun `stub plugins are internal plugins carrying their source`() {
        val source = PluginSource(repo)
        val stub = stubPlugin(manifest("test.stub"), source)

        assertEquals(source, stub.defaultSource)
        assertTrue(InternalPluginFlags.INTERNAL in stub.internalFlags)
        assertNull(internalPlugin(manifest("test.plain")) {}.defaultSource)
    }

    @Test
    fun `stub plugins need a source repository`() {
        assertFailsWith<IllegalArgumentException> { stubPlugin(manifest("test.stub"), PluginSource(repo = null)) }
    }

    @Test
    fun `reserved plugins can't be stubs`() {
        assertFailsWith<IllegalArgumentException> { stubPlugin(manifest(API_DEPENDENCY_ID), PluginSource(repo)) }
    }

    @Test
    fun `seeding never overrides existing provenance`() {
        val stub = stubPlugin(manifest("test.stub"), PluginSource(repo))
        val other = stubPlugin(manifest("test.other"), PluginSource(repo))
        val plain = internalPlugin(manifest("test.plain")) {}
        val existing = mapOf("test.other" to PluginSource(repo = null))

        val seeds = defaultSourcesToSeed(listOf(stub, other, plain), existing)

        assertEquals(mapOf("test.stub" to PluginSource(repo)), seeds)
    }

    @Test
    fun `internals are shadowed only with provenance and an installed copy`() {
        val internals = listOf("a", "b", "c", "d").map { internalPlugin(manifest(it)) {} }
        val sources = mapOf("a" to PluginSource(repo), "b" to PluginSource(repo), "d" to PluginSource(repo = null))
        val installed = setOf("a", "c", "d")

        val shadowed = shadowedInternalIds(internals, sources) { it in installed }

        // "b" has no copy on disk, "c" has no provenance. Sideloaded provenance still counts.
        assertEquals(setOf("a", "d"), shadowed)
    }
}
