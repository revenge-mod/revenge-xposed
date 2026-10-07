package io.github.revenge.xposed.tweaks.plugins.repos

import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange
import io.github.revenge.xposed.tweaks.plugins.external.ExternalDependency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val REPO = "https://a.example/plugins"
private val SHA = "a".repeat(64)

private fun version(size: Long = 1, vararg deps: Pair<String, String?>) = RepoVersion(
    url = "https://a.example/artifact.zip",
    sha256 = SHA,
    size = size,
    dependencies = deps.associate { (id, range) -> id to ExternalDependency(version = range) },
)

private fun plugin(latest: String, vararg versions: Pair<String, RepoVersion>) =
    RepoPlugin(name = "Test", channels = mapOf("latest" to latest), versions = versions.toMap())

private fun repos(vararg plugins: Pair<String, RepoPlugin>) =
    listOf(REPO to RepoIndex(format = REPO_INDEX_FORMAT, name = "Test Repo", plugins = plugins.toMap()))

private fun v(version: String) = Version.parse(version)

private fun source(held: Boolean = false) = PluginSource(repo = REPO, hash = SHA, held = held)

class UpdatesTest {
    @Test
    fun `lists newer versions with size and pulled in dependency updates`() {
        val updates = findUpdates(
            repos(
                "com.example.app" to plugin("2.0.0", "2.0.0" to version(size = 42, "com.example.lib" to ">=2")),
                "com.example.lib" to plugin("2.0.0", "2.0.0" to version()),
            ),
            installed = mapOf("com.example.app" to v("1.0.0"), "com.example.lib" to v("1.0.0")),
            pending = emptyMap(),
            sources = mapOf("com.example.app" to source(), "com.example.lib" to source()),
            installedDependencies = emptyMap(),
        )

        val app = updates.single { it.id == "com.example.app" }
        assertEquals(42, app.size)
        assertEquals(listOf("com.example.lib"), app.includes.map { it.id })
        assertNull(app.blocker)
        assertTrue(app.warnings.isEmpty())
    }

    @Test
    fun `pending updates are not offered again`() {
        val updates = findUpdates(
            repos("com.example.app" to plugin("2.0.0", "2.0.0" to version())),
            installed = mapOf("com.example.app" to v("1.0.0")),
            pending = mapOf("com.example.app" to v("2.0.0")),
            sources = mapOf("com.example.app" to source()),
            installedDependencies = emptyMap(),
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `held plugins are listed as blocked`() {
        val updates = findUpdates(
            repos("com.example.app" to plugin("2.0.0", "2.0.0" to version())),
            installed = mapOf("com.example.app" to v("1.0.0")),
            pending = emptyMap(),
            sources = mapOf("com.example.app" to source(held = true)),
            installedDependencies = emptyMap(),
        )

        val update = updates.single()
        assertEquals(v("2.0.0"), update.available)
        assertTrue(update.blocker is ResolveIssue.Held)
    }

    @Test
    fun `a held dependency blocks the update`() {
        val updates = findUpdates(
            repos(
                "com.example.app" to plugin("2.0.0", "2.0.0" to version(deps = arrayOf("com.example.lib" to ">=2"))),
                "com.example.lib" to plugin("2.0.0", "2.0.0" to version()),
            ),
            installed = mapOf("com.example.app" to v("1.0.0"), "com.example.lib" to v("1.0.0")),
            pending = emptyMap(),
            sources = mapOf("com.example.app" to source(), "com.example.lib" to source(held = true)),
            installedDependencies = emptyMap(),
        )

        val blocker = updates.single { it.id == "com.example.app" }.blocker as ResolveIssue.Unresolved
        assertEquals("com.example.lib", blocker.id)
        assertEquals(UnresolvedReason.HELD, blocker.reason)
    }

    @Test
    fun `a break is fixed by the broken dependent's own update`() {
        val updates = findUpdates(
            repos(
                "com.example.lib" to plugin("2.0.0", "2.0.0" to version()),
                "com.example.fixed" to plugin("2.0.0", "2.0.0" to version(deps = arrayOf("com.example.lib" to ">=2"))),
            ),
            installed = mapOf(
                "com.example.lib" to v("1.0.0"),
                "com.example.fixed" to v("1.0.0"),
                "com.example.stale" to v("1.0.0"),
            ),
            pending = emptyMap(),
            sources = mapOf("com.example.lib" to source(), "com.example.fixed" to source()),
            installedDependencies = mapOf(
                "com.example.fixed" to mapOf("com.example.lib" to VersionRange.parse("<2")),
                "com.example.stale" to mapOf("com.example.lib" to VersionRange.parse("<2")),
            ),
        )

        val breaks = updates.single { it.id == "com.example.lib" }.warnings
            .filterIsInstance<ResolveIssue.Breaks>()
            .associate { it.dependent to it.fixedBy }
        assertEquals(mapOf("com.example.fixed" to "com.example.fixed", "com.example.stale" to null), breaks)
    }
}
