package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.logger
import io.github.revenge.plugins.API_DEPENDENCY_ID
import io.github.revenge.plugins.DISCORD_DEPENDENCY_ID
import io.github.revenge.plugins.Version
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Unsatisfied/missing dependencies, cascades, cycles, and unparseable manifests must surface as [DiscoveryFailure]s
 * instead of vanishing and appearing in logs only.
 */
class DiscoveryFailureTest {
    private val dataDir: File = Files.createTempDirectory("revenge-discovery-test").toFile()
    private val log = logger("test")

    private val knownVersions = mapOf(
        API_DEPENDENCY_ID to Version.parse("1.0.0"),
        DISCORD_DEPENDENCY_ID to Version.parse("0"),
    )

    @AfterTest
    fun cleanup() {
        dataDir.deleteRecursively()
    }

    private fun writePlugin(
        id: String,
        version: String = "1.0.0",
        dependencies: String = "",
        manifestJson: String? = null,
    ) {
        val dir = File(externalPluginsRoot(dataDir.absolutePath), id).apply { mkdirs() }
        val deps = buildString {
            append("\"$API_DEPENDENCY_ID\": {}, \"$DISCORD_DEPENDENCY_ID\": {}")
            if (dependencies.isNotEmpty()) append(", $dependencies")
        }
        File(dir, "manifest.json").writeText(
            manifestJson ?: """
            {
                "format": 1,
                "id": "$id",
                "name": "Test $id",
                "version": "$version",
                "dependencies": { $deps },
                "dist": { "script": "plugin.js" }
            }
            """.trimIndent(),
        )
        File(dir, "plugin.js").writeText("() => ({})")
    }

    private fun discover() = discoverExternalPlugins(dataDir.absolutePath, knownVersions, log)

    @Test
    fun `valid plugin loads with no failures`() {
        writePlugin("com.example.ok")

        val discovery = discover()

        assertEquals(listOf("com.example.ok"), discovery.factories.map { it.manifest.id })
        assertTrue(discovery.failures.isEmpty())
    }

    @Test
    fun `missing required dependency session-skips with a parsed manifest and reason`() {
        writePlugin("com.example.broken", dependencies = "\"com.example.gone\": {}")

        val discovery = discover()

        assertTrue(discovery.factories.isEmpty())
        val failure = discovery.failures["com.example.broken"]
        assertNotNull(failure)
        assertNotNull(failure.manifest)
        assertEquals("com.example.broken", failure.manifest?.id)
        assertEquals(PluginErrorCodes.DEPENDENCY_MISSING, failure.code)
        assertTrue("missing dependency 'com.example.gone'" in failure.reason)
    }

    @Test
    fun `version-unsatisfied required dependency session-skips`() {
        writePlugin("com.example.dep", version = "1.0.0")
        writePlugin("com.example.needy", dependencies = "\"com.example.dep\": { \"version\": \">=2\" }")

        val discovery = discover()

        assertEquals(listOf("com.example.dep"), discovery.factories.map { it.manifest.id })
        val failure = discovery.failures["com.example.needy"]
        assertNotNull(failure)
        assertEquals(PluginErrorCodes.DEPENDENCY_UNSATISFIED, failure.code)
        assertTrue("does not satisfy" in failure.reason)
    }

    @Test
    fun `required-dependency failure cascades to dependents`() {
        writePlugin("com.example.mid", dependencies = "\"com.example.gone\": {}")
        writePlugin("com.example.top", dependencies = "\"com.example.mid\": {}")

        val discovery = discover()

        assertTrue(discovery.factories.isEmpty())
        assertNotNull(discovery.failures["com.example.mid"])
        val top = discovery.failures["com.example.top"]
        assertNotNull(top)
        assertEquals(PluginErrorCodes.DEPENDENCY_MISSING, top.code)
        assertTrue("com.example.mid" in top.reason)
    }

    @Test
    fun `missing optional dependency never blocks the dependent`() {
        writePlugin("com.example.tolerant", dependencies = "\"com.example.gone\": { \"optional\": true }")

        val discovery = discover()

        assertEquals(listOf("com.example.tolerant"), discovery.factories.map { it.manifest.id })
        assertTrue(discovery.failures.isEmpty())
        assertTrue(discovery.factories.single().unsatisfiedOptionalDependencies.isEmpty())
    }

    @Test
    fun `present-but-unsatisfied optional dependency is reported`() {
        writePlugin("com.example.dep", version = "1.0.0")
        writePlugin(
            "com.example.tolerant",
            dependencies = "\"com.example.dep\": { \"version\": \">=2\", \"optional\": true }",
        )

        val discovery = discover()

        assertEquals(
            setOf("com.example.dep", "com.example.tolerant"),
            discovery.factories.map { it.manifest.id }.toSet(),
        )
        assertTrue(discovery.failures.isEmpty())
        val tolerant = discovery.factories.single { it.manifest.id == "com.example.tolerant" }
        assertEquals(setOf("com.example.dep"), tolerant.unsatisfiedOptionalDependencies)
    }

    @Test
    fun `satisfied optional dependency is not reported`() {
        writePlugin("com.example.dep", version = "2.0.0")
        writePlugin(
            "com.example.tolerant",
            dependencies = "\"com.example.dep\": { \"version\": \">=2\", \"optional\": true }",
        )

        val discovery = discover()

        assertTrue(discovery.failures.isEmpty())
        val tolerant = discovery.factories.single { it.manifest.id == "com.example.tolerant" }
        assertTrue(tolerant.unsatisfiedOptionalDependencies.isEmpty())
    }

    @Test
    fun `dependency cycle session-skips every member`() {
        writePlugin("com.example.a", dependencies = "\"com.example.b\": {}")
        writePlugin("com.example.b", dependencies = "\"com.example.a\": {}")

        val discovery = discover()

        assertTrue(discovery.factories.isEmpty())
        assertEquals(PluginErrorCodes.DEPENDENCY_CYCLE, discovery.failures["com.example.a"]?.code)
        assertTrue(discovery.failures["com.example.a"]?.reason?.contains("cycle") == true)
        assertTrue(discovery.failures["com.example.b"]?.reason?.contains("cycle") == true)
    }

    @Test
    fun `unparseable manifest fails keyed by directory name with no manifest`() {
        writePlugin("com.example.junk", manifestJson = "{ not json")

        val discovery = discover()

        assertTrue(discovery.factories.isEmpty())
        val failure = discovery.failures["com.example.junk"]
        assertNotNull(failure)
        assertEquals(PluginErrorCodes.MANIFEST_INVALID, failure.code)
        assertNull(failure.manifest)
    }

    @Test
    fun `manifest missing a reserved dependency is rejected at parse`() {
        writePlugin(
            "com.example.undeclared",
            manifestJson = """
            {
                "format": 1,
                "id": "com.example.undeclared",
                "name": "Undeclared",
                "version": "1.0.0",
                "dependencies": { "$API_DEPENDENCY_ID": {} }
            }
            """.trimIndent(),
        )

        val discovery = discover()

        assertTrue(discovery.factories.isEmpty())
        val failure = discovery.failures["com.example.undeclared"]
        assertNotNull(failure)
        assertNull(failure.manifest)
    }

    @Test
    fun `failures never remove plugin directories`() {
        writePlugin("com.example.broken", dependencies = "\"com.example.gone\": {}")

        discover()

        assertTrue(File(externalPluginsRoot(dataDir.absolutePath), "com.example.broken").isDirectory)
    }

    @Test
    fun `plugins load in dependency order`() {
        writePlugin("com.example.top", dependencies = "\"com.example.dep\": {}")
        writePlugin("com.example.dep")

        val discovery = discover()

        assertEquals(
            listOf("com.example.dep", "com.example.top"),
            discovery.factories.map { it.manifest.id },
        )
        assertTrue(discovery.failures.isEmpty())
    }

    @Test
    fun `discovery ignores dot-directories and cleans up orphaned temp dirs`() {
        writePlugin("com.example.ok")
        // A crash-orphaned staged install: contains a valid manifest but must never be discovered.
        val orphan = File(externalPluginsRoot(dataDir.absolutePath), ".confirm-abc").apply { mkdirs() }
        File(orphan, "manifest.json").writeText(
            File(File(externalPluginsRoot(dataDir.absolutePath), "com.example.ok"), "manifest.json").readText(),
        )
        val dexCache = File(externalPluginsRoot(dataDir.absolutePath), ".dex-cache").apply { mkdirs() }

        val discovery = discover()

        assertEquals(listOf("com.example.ok"), discovery.factories.map { it.manifest.id })
        assertTrue(discovery.failures.isEmpty())
        assertTrue(!orphan.exists(), "orphaned staged dir should be cleaned up")
        assertTrue(dexCache.isDirectory, "the dex cache must survive cleanup")
    }
}
