package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.Version
import io.github.revenge.plugins.plugin
import io.github.revenge.xposed.tweaks.plugins.external.nativePluginLoaders
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A plugin's code must not load until it is built.
 * It must also not reload while its class loader chain is unchanged, or the same names end up with two [Class] objects.
 */
class PluginFactoryTest {
    private val manifest = PluginManifest("com.example.test", "Test", "", "", version = Version.parse("1.0.0"))

    @AfterTest
    fun tearDown() = nativePluginLoaders.clear()

    /** Counts how many times the plugin's code was loaded. */
    private fun countingFactory(loads: () -> Unit) = PluginFactory(manifest) {
        loads()
        plugin {}
    }

    @Test
    fun `nothing loads until the plugin is built`() {
        var loads = 0
        val factory = countingFactory { loads++ }

        assertEquals(0, loads)
        assertNull(factory.chainedDependencies)
    }

    @Test
    fun `an unchanged chain reuses the loaded code`() {
        var loads = 0
        val factory = countingFactory { loads++ }

        factory.build(setOf("com.example.dep"))
        factory.build(setOf("com.example.dep"))

        assertEquals(1, loads)
        assertEquals(setOf("com.example.dep"), factory.chainedDependencies)
    }

    @Test
    fun `a new chain reloads and records the new one`() {
        var loads = 0
        val factory = countingFactory { loads++ }

        factory.build(setOf("com.example.dep"))
        factory.build(emptySet())

        assertEquals(2, loads)
        assertEquals(emptySet(), factory.chainedDependencies)
    }

    @Test
    fun `a dependency that relinks reloads the plugin`() {
        var loads = 0
        val factory = countingFactory { loads++ }
        val chain = setOf("com.example.dep")

        nativePluginLoaders["com.example.dep"] = object : ClassLoader() {}
        factory.build(chain)

        // New class loader. Rebuilding should relink.
        nativePluginLoaders["com.example.dep"] = object : ClassLoader() {}
        factory.build(chain)

        assertEquals(2, loads)
    }

    @Test
    fun `every build has the manifest`() {
        val factory = countingFactory {}

        assertEquals(manifest, factory.build(emptySet()).manifest)
    }
}
