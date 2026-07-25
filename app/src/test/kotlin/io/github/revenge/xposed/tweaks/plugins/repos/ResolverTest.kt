package io.github.revenge.xposed.tweaks.plugins.repos

import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange
import io.github.revenge.xposed.tweaks.plugins.ExternalDependency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val REPO_A = "https://a.example/plugins"
private const val REPO_B = "https://b.example/plugins"
private val DUMMY_SHA = "a".repeat(64)

private fun version(
    url: String = "https://a.example/artifact.zip",
    vararg deps: Pair<String, ExternalDependency>,
) = RepoVersion(url = url, sha256 = DUMMY_SHA, size = 1, dependencies = deps.toMap())

private fun dep(range: String? = null, optional: Boolean = false) =
    ExternalDependency(version = range, optional = optional)

private fun plugin(
    channels: Map<String, String> = emptyMap(),
    versions: Map<String, RepoVersion>,
) = RepoPlugin(name = "Test", channels = channels, versions = versions)

private fun index(vararg plugins: Pair<String, RepoPlugin>) =
    RepoIndex(format = REPO_INDEX_FORMAT, name = "Test Repo", plugins = plugins.toMap())

private val API = mapOf("revenge.api" to Version.parse("1.0.0"))

class ResolverTest {
    @Test
    fun `fresh install plans the root and its transitive dependencies`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    versions = mapOf("1.0.0" to version(deps = arrayOf("com.example.b" to dep(">=1")))),
                ),
                "com.example.b" to plugin(versions = mapOf("1.2.0" to version())),
            ),
        )

        val plan = resolveInstall(ResolveRequest("com.example.a"), repos, API, emptyMap())

        assertEquals(listOf("com.example.a", "com.example.b"), plan.actions.map { it.id })
        assertEquals(Version.parse("1.2.0"), plan.actions[1].version)
        assertTrue(plan.actions.all { it.replaces == null })
    }

    @Test
    fun `channel pointer wins over newest`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    channels = mapOf("latest" to "1.0.0"),
                    versions = mapOf("1.0.0" to version(), "2.0.0" to version()),
                ),
            ),
        )

        val plan = resolveInstall(ResolveRequest("com.example.a"), repos, API, emptyMap())
        assertEquals(Version.parse("1.0.0"), plan.actions.single().version)
    }

    @Test
    fun `without channel pointer the newest non-labeled version is selected`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    versions = mapOf(
                        "1.0.0" to version(),
                        "2.0.0-beta1" to version(),
                        "1.5.0" to version(),
                    ),
                ),
            ),
        )

        val plan = resolveInstall(ResolveRequest("com.example.a"), repos, API, emptyMap())
        assertEquals(Version.parse("1.5.0"), plan.actions.single().version)
    }

    @Test
    fun `installed plugins are pinned to their provenance repository`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(versions = mapOf("2.0.0" to version(url = "https://a.example/a2.zip"))),
            ),
            REPO_B to index(
                "com.example.a" to plugin(versions = mapOf("3.0.0" to version(url = "https://b.example/a3.zip"))),
            ),
        )

        val plan = resolveInstall(
            ResolveRequest("com.example.a"),
            repos,
            API + ("com.example.a" to Version.parse("1.0.0")),
            mapOf("com.example.a" to PluginSource(repo = REPO_B)),
        )

        val action = plan.actions.single()
        assertEquals(REPO_B, action.repo)
        assertEquals(Version.parse("3.0.0"), action.version)
        assertEquals(Version.parse("1.0.0"), action.replaces)
    }

    @Test
    fun `unresolvable optional dependency is skipped with a warning`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    versions = mapOf(
                        "1.0.0" to version(deps = arrayOf("com.example.missing" to dep(optional = true))),
                    ),
                ),
            ),
        )

        val plan = resolveInstall(ResolveRequest("com.example.a"), repos, API, emptyMap())
        assertEquals(listOf("com.example.a"), plan.actions.map { it.id })
        assertTrue(plan.warnings.any { "com.example.missing" in it })
    }

    @Test
    fun `unresolvable required dependency aborts`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    versions = mapOf("1.0.0" to version(deps = arrayOf("com.example.missing" to dep()))),
                ),
            ),
        )

        assertFailsWith<ResolveException> {
            resolveInstall(ResolveRequest("com.example.a"), repos, API, emptyMap())
        }
    }

    @Test
    fun `satisfied dependencies produce no actions`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    versions = mapOf(
                        "1.0.0" to version(
                            deps = arrayOf(
                                "revenge.api" to dep(">=1"),
                                "com.example.b" to dep(">=1 <2"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val plan = resolveInstall(
            ResolveRequest("com.example.a"),
            repos,
            API + ("com.example.b" to Version.parse("1.5.0")),
            emptyMap(),
        )
        assertEquals(listOf("com.example.a"), plan.actions.map { it.id })
    }

    @Test
    fun `already installed at target version yields an empty plan`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    channels = mapOf("latest" to "1.0.0"),
                    versions = mapOf("1.0.0" to version()),
                ),
            ),
        )

        val plan = resolveInstall(
            ResolveRequest("com.example.a"),
            repos,
            API + ("com.example.a" to Version.parse("1.0.0")),
            mapOf("com.example.a" to PluginSource(repo = REPO_A)),
        )
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun `exact version request downgrades with a warning`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    versions = mapOf("1.0.0" to version(), "2.0.0" to version()),
                ),
            ),
        )

        val plan = resolveInstall(
            ResolveRequest("com.example.a", version = "1.0.0"),
            repos,
            API + ("com.example.a" to Version.parse("2.0.0")),
            mapOf("com.example.a" to PluginSource(repo = REPO_A)),
        )

        assertEquals(Version.parse("1.0.0"), plan.actions.single().version)
        assertTrue(plan.warnings.any { "Downgrading" in it })
    }

    @Test
    fun `planned version breaking an installed dependent warns but never blocks`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.lib" to plugin(
                    channels = mapOf("latest" to "2.0.0"),
                    versions = mapOf("2.0.0" to version()),
                ),
            ),
        )

        val plan = resolveInstall(
            ResolveRequest("com.example.lib"),
            repos,
            API + ("com.example.lib" to Version.parse("1.0.0")),
            mapOf("com.example.lib" to PluginSource(repo = REPO_A)),
            installedDependencies = mapOf(
                "com.example.consumer" to mapOf("com.example.lib" to VersionRange.parse(">=1 <2")),
            ),
        )

        assertEquals(Version.parse("2.0.0"), plan.actions.single().version)
        assertTrue(plan.warnings.any { "com.example.consumer" in it })
    }
}
