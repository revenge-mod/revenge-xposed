package io.github.revenge.xposed.tweaks.plugins

import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Recovery's defaults-only mode is a read overlay.
 */
class DefaultsOnlyStatesTest {
    private val dataDir: File = Files.createTempDirectory("revenge-defaults-only-test").toFile()
    private val statesDir = File(dataDir, "files/revenge/plugins")

    @BeforeTest
    fun reset() = PluginStatesStore.resetForTests()

    @AfterTest
    fun cleanup() {
        PluginStatesStore.resetForTests()
        dataDir.deleteRecursively()
    }

    private fun loadWith(vararg enabled: String): PluginsStates {
        val states = PluginStatesStore.ensureLoaded(dataDir.absolutePath)
        for (id in enabled) states.setPluginFlags(id, setOf(PluginFlags.ENABLED))
        return states
    }

    @Test
    fun `normal boot reads saved states`() {
        val states = loadWith("com.example.plugin")

        assertFalse(PluginStatesStore.defaultsOnly)
        assertTrue(states.isPluginEnabled("com.example.plugin"))
        assertTrue(states.hasPlugin("com.example.plugin"))
        assertEquals(setOf(PluginFlags.ENABLED), PluginStatesStore.loadPluginFlags("com.example.plugin"))
    }

    @Test
    fun `defaults-only reads answer as if nothing was saved`() {
        PluginStatesStore.requestDefaultsOnlyBoot(dataDir.absolutePath)
        val states = loadWith("com.example.plugin")

        assertTrue(PluginStatesStore.defaultsOnly)
        assertFalse(states.isPluginEnabled("com.example.plugin"))
        // hasPlugin false is what makes enabled-by-default plugins load
        assertFalse(states.hasPlugin("com.example.plugin"))
        assertNull(PluginStatesStore.loadPluginFlags("com.example.plugin"))

        // The underlying map is untouched, so a normal reload restores everything
        assertEquals(PluginFlags.ENABLED.bit.toDouble(), states.flags["com.example.plugin"])
    }

    @Test
    fun `defaults-only hides states from JS too`() {
        PluginStatesStore.requestDefaultsOnlyBoot(dataDir.absolutePath)
        val states = loadWith("com.example.plugin")

        @Suppress("UNCHECKED_CAST")
        val payload = states.toMap()["states"] as Map<String, Any>
        assertTrue(payload.isEmpty(), "JS must agree with native or it runs plugins native skipped")
    }

    @Test
    fun `writes during defaults-only land in the real map`() {
        PluginStatesStore.requestDefaultsOnlyBoot(dataDir.absolutePath)
        val states = loadWith("com.example.plugin")

        // Disabling the culprit while in the mode has to stick
        states.setPluginFlags("com.example.plugin", emptySet())
        assertEquals(0.0, states.flags["com.example.plugin"])

        states.setPluginFlags("com.example.other", setOf(PluginFlags.ENABLED))
        assertEquals(PluginFlags.ENABLED.bit.toDouble(), states.flags["com.example.other"])
    }

    @Test
    fun `defaults-only sends the saved setup separately`() {
        PluginStatesStore.requestDefaultsOnlyBoot(dataDir.absolutePath)
        val states = loadWith("com.example.plugin")

        val payload = states.toMap()

        @Suppress("UNCHECKED_CAST")
        val effective = payload["states"] as Map<String, Any>
        assertTrue(effective.isEmpty())

        // The UI needs the real setup to show and edit what applies on the next reload
        @Suppress("UNCHECKED_CAST")
        val saved = payload["savedStates"] as Map<String, Map<String, Any>>
        assertEquals(true, saved["com.example.plugin"]?.get("enabled"))

        assertTrue(states.isPluginEnabledInSaved("com.example.plugin"))
        assertTrue(states.hasPluginInSaved("com.example.plugin"))
    }

    @Test
    fun `normal boot sends no saved setup`() {
        val states = loadWith("com.example.plugin")

        val payload = states.toMap()
        assertFalse("savedStates" in payload, "effective states already are the saved ones")
    }

    @Test
    fun `marker is consumed once`() {
        PluginStatesStore.requestDefaultsOnlyBoot(dataDir.absolutePath)
        assertTrue(File(statesDir, ".defaults-only").exists())

        PluginStatesStore.ensureLoaded(dataDir.absolutePath)
        assertTrue(PluginStatesStore.defaultsOnly)
        assertFalse(File(statesDir, ".defaults-only").exists(), "deleted before any plugin loads")

        // Next boot is normal again
        PluginStatesStore.resetForTests()
        PluginStatesStore.ensureLoaded(dataDir.absolutePath)
        assertFalse(PluginStatesStore.defaultsOnly)
    }
}
