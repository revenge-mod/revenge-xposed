package io.github.revenge.xposed.tweaks.plugins.repos

import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange
import io.github.revenge.xposed.tweaks.plugins.external.ExternalDependency
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
    fun `planned dependencies record who pulled them in and whether optionally`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    versions = mapOf(
                        "1.0.0" to version(
                            deps = arrayOf(
                                "com.example.b" to dep(optional = true),
                                "com.example.c" to dep(),
                            ),
                        ),
                    ),
                ),
                "com.example.b" to plugin(versions = mapOf("1.0.0" to version())),
                "com.example.c" to plugin(
                    versions = mapOf("1.0.0" to version(deps = arrayOf("com.example.b" to dep()))),
                ),
            ),
        )

        val plan = resolveInstall(ResolveRequest("com.example.a"), repos, API, emptyMap())
        val byId = plan.actions.associateBy { it.id }

        fun optionality(id: String) = byId.getValue(id).dependents.mapValues { it.value.optional }

        assertTrue(byId.getValue("com.example.a").dependents.isEmpty())
        assertEquals(mapOf("com.example.a" to false), optionality("com.example.c"))
        assertEquals(mapOf("com.example.a" to true, "com.example.c" to false), optionality("com.example.b"))
    }

    private fun targetRepos() = listOf(
        REPO_A to index(
            "com.example.a" to plugin(
                versions = mapOf("1.0.0" to version(deps = arrayOf("com.example.opt" to dep(">=1.0 <2", optional = true)))),
            ),
            "com.example.req" to plugin(versions = mapOf("1.0.0" to version(), "2.0.0" to version())),
            "com.example.opt" to plugin(
                channels = mapOf("latest" to "1.2.0", "beta" to "2.0.0-beta.1"),
                versions = mapOf("1.0.0" to version(), "1.2.0" to version(), "2.0.0-beta.1" to version()),
            ),
        ),
        REPO_B to index(
            "com.example.opt" to plugin(
                versions = mapOf("1.1.0" to version(url = "https://b.example/opt.zip"), "3.0.0" to version()),
            ),
        ),
    )

    @Test
    fun `targets pick a dependency's repository and version within its range`() {
        val plan = resolveInstall(
            ResolveRequest(
                "com.example.a",
                mapOf("com.example.opt" to PlanTarget(repo = REPO_B, version = "1.1.0")),
            ),
            targetRepos(),
            API,
            emptyMap(),
        )

        val opt = plan.actions.single { it.id == "com.example.opt" }
        assertEquals(REPO_B, opt.repo)
        assertEquals(Version.parse("1.1.0"), opt.version)
        assertEquals(true, opt.hold)
        assertEquals(null, plan.actions.single { it.id == "com.example.a" }.hold)
    }

    @Test
    fun `a target outside an optional dependency's range skips it with a warning`() {
        val plan = resolveInstall(
            ResolveRequest("com.example.a", mapOf("com.example.opt" to PlanTarget(channel = "beta"))),
            targetRepos(),
            API,
            emptyMap(),
        )

        assertEquals(listOf("com.example.a"), plan.actions.map { it.id })
        assertTrue(plan.warnings.any { "com.example.opt" in it && "skipped" in it })
    }

    @Test
    fun `a target outside a required dependency's range fails`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    versions = mapOf("1.0.0" to version(deps = arrayOf("com.example.req" to dep(">=1.0 <2")))),
                ),
                "com.example.req" to plugin(versions = mapOf("1.0.0" to version(), "2.0.0" to version())),
            ),
        )

        assertFailsWith<PluginSystemResolveException> {
            resolveInstall(
                ResolveRequest("com.example.a", mapOf("com.example.req" to PlanTarget(version = "2.0.0"))),
                repos,
                API,
                emptyMap(),
            )
        }
    }

    @Test
    fun `candidates list versions satisfying planned dependents per repository`() {
        val plan = resolveInstall(ResolveRequest("com.example.a"), targetRepos(), API, emptyMap())
        val opt = plan.actions.single { it.id == "com.example.opt" }

        assertEquals(setOf("1.0.0", "1.2.0"), opt.candidates.getValue(REPO_A).keys)
        assertEquals(setOf("1.1.0"), opt.candidates.getValue(REPO_B).keys)
        // The root has no planned dependents, every version is a candidate
        assertEquals(setOf("1.0.0"), plan.actions.single { it.id == "com.example.a" }.candidates.getValue(REPO_A).keys)
    }

    @Test
    fun `candidates mark installed plugins outside the plan a version would break`() {
        val plan = resolveInstall(
            ResolveRequest("com.example.req"),
            targetRepos(),
            API,
            emptyMap(),
            installedDependencies = mapOf("com.example.user" to mapOf("com.example.req" to VersionRange.parse(">=1.0 <2"))),
        )

        val candidates = plan.actions.single().candidates.getValue(REPO_A)
        assertEquals(emptyList(), candidates.getValue("1.0.0").breaks)
        assertEquals(listOf("com.example.user"), candidates.getValue("2.0.0").breaks)
    }

    @Test
    fun `a channel target resumes updates and a matching held install is replanned`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(channels = mapOf("latest" to "1.0.0"), versions = mapOf("1.0.0" to version())),
            ),
        )

        val plan = resolveInstall(
            ResolveRequest("com.example.a", mapOf("com.example.a" to PlanTarget(channel = "latest"))),
            repos,
            API + ("com.example.a" to Version.parse("1.0.0")),
            mapOf("com.example.a" to PluginSource(repo = REPO_A, hash = DUMMY_SHA, held = true)),
        )

        assertEquals(false, plan.actions.single().hold)
    }

    @Test
    fun `targets for plugins outside the plan are ignored`() {
        val plan = resolveInstall(
            ResolveRequest("com.example.req", mapOf("com.example.nope" to PlanTarget(version = "1.0.0"))),
            targetRepos(),
            API,
            emptyMap(),
        )

        assertEquals(listOf("com.example.req"), plan.actions.map { it.id })
        assertTrue(plan.warnings.none { "com.example.nope" in it })
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
    fun `requested repository overrides the root's provenance but not its dependencies'`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    versions = mapOf(
                        "2.0.0" to version(
                            url = "https://a.example/a2.zip",
                            "com.example.lib" to dep(">=2.0.0")
                        )
                    ),
                ),
                "com.example.lib" to plugin(versions = mapOf("2.0.0" to version(url = "https://a.example/lib2.zip"))),
            ),
            REPO_B to index(
                "com.example.a" to plugin(versions = mapOf("3.0.0" to version(url = "https://b.example/a3.zip"))),
                "com.example.lib" to plugin(versions = mapOf("2.0.0" to version(url = "https://b.example/lib2.zip"))),
            ),
        )

        val plan = resolveInstall(
            ResolveRequest("com.example.a", mapOf("com.example.a" to PlanTarget(repo = REPO_A))),
            repos,
            API + ("com.example.a" to Version.parse("1.0.0")) + ("com.example.lib" to Version.parse("1.0.0")),
            mapOf(
                "com.example.a" to PluginSource(repo = REPO_B),
                "com.example.lib" to PluginSource(repo = REPO_B),
            ),
        )

        val byId = plan.actions.associateBy { it.id }
        assertEquals(REPO_A, byId.getValue("com.example.a").repo)
        assertEquals(Version.parse("2.0.0"), byId.getValue("com.example.a").version)
        assertEquals(REPO_B, byId.getValue("com.example.lib").repo)
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

        assertFailsWith<PluginSystemResolveException> {
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
    fun `same version is replanned when the source would change`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    channels = mapOf("latest" to "1.0.0", "beta" to "1.0.0"),
                    versions = mapOf("1.0.0" to version()),
                ),
            ),
            REPO_B to index(
                "com.example.a" to plugin(
                    channels = mapOf("latest" to "1.0.0"),
                    versions = mapOf("1.0.0" to version(url = "https://b.example/a1.zip")),
                ),
            ),
        )
        val installed = API + ("com.example.a" to Version.parse("1.0.0"))
        val source = PluginSource(repo = REPO_A, hash = DUMMY_SHA)

        fun plan(request: ResolveRequest, from: PluginSource = source) =
            resolveInstall(request, repos, installed, mapOf("com.example.a" to from))

        assertTrue(plan(ResolveRequest("com.example.a")).actions.isEmpty())
        // Unknown hash counts as the same artifact
        assertTrue(plan(ResolveRequest("com.example.a"), source.copy(hash = null)).actions.isEmpty())

        val moved = plan(ResolveRequest("com.example.a", mapOf("com.example.a" to PlanTarget(repo = REPO_B)))).actions.single()
        assertEquals(REPO_B, moved.repo)
        assertEquals(Version.parse("1.0.0"), moved.replaces)

        assertEquals(1, plan(ResolveRequest("com.example.a", mapOf("com.example.a" to PlanTarget(channel = "beta")))).actions.size)
        assertEquals(1, plan(ResolveRequest("com.example.a"), source.copy(hash = "b".repeat(64))).actions.size)
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
            ResolveRequest("com.example.a", mapOf("com.example.a" to PlanTarget(version = "1.0.0"))),
            repos,
            API + ("com.example.a" to Version.parse("2.0.0")),
            mapOf("com.example.a" to PluginSource(repo = REPO_A)),
        )

        assertEquals(Version.parse("1.0.0"), plan.actions.single().version)
        assertTrue(plan.warnings.any { "Downgrading" in it })
    }

    @Test
    fun `a held dependency that already satisfies the range needs no action`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    versions = mapOf("1.0.0" to version(deps = arrayOf("com.example.lib" to dep(">=1 <2")))),
                ),
                "com.example.lib" to plugin(versions = mapOf("1.5.0" to version(), "1.9.0" to version())),
            ),
        )

        val plan = resolveInstall(
            ResolveRequest("com.example.a"),
            repos,
            API + ("com.example.lib" to Version.parse("1.5.0")),
            mapOf("com.example.lib" to PluginSource(repo = REPO_A, held = true)),
        )

        assertEquals(listOf("com.example.a"), plan.actions.map { it.id })
    }

    @Test
    fun `a held dependency that does not satisfy aborts instead of updating it`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    versions = mapOf("1.0.0" to version(deps = arrayOf("com.example.lib" to dep(">=2")))),
                ),
                "com.example.lib" to plugin(versions = mapOf("1.0.0" to version(), "2.0.0" to version())),
            ),
        )

        val error = assertFailsWith<PluginSystemResolveException> {
            resolveInstall(
                ResolveRequest("com.example.a"),
                repos,
                API + ("com.example.lib" to Version.parse("1.0.0")),
                mapOf("com.example.lib" to PluginSource(repo = REPO_A, held = true)),
            )
        }

        assertTrue("held" in error.message!!, error.message!!)
    }

    @Test
    fun `a held optional dependency that does not satisfy is skipped with a warning`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(
                    versions = mapOf(
                        "1.0.0" to version(deps = arrayOf("com.example.lib" to dep(">=2", optional = true))),
                    ),
                ),
                "com.example.lib" to plugin(versions = mapOf("1.0.0" to version(), "2.0.0" to version())),
            ),
        )

        val plan = resolveInstall(
            ResolveRequest("com.example.a"),
            repos,
            API + ("com.example.lib" to Version.parse("1.0.0")),
            mapOf("com.example.lib" to PluginSource(repo = REPO_A, held = true)),
        )

        assertEquals(listOf("com.example.a"), plan.actions.map { it.id })
        assertTrue(plan.warnings.any { "com.example.lib" in it })
    }

    @Test
    fun `a hold never constrains the requested plugin itself`() {
        val repos = listOf(
            REPO_A to index(
                "com.example.a" to plugin(versions = mapOf("1.0.0" to version(), "2.0.0" to version())),
            ),
        )

        val plan = resolveInstall(
            ResolveRequest("com.example.a", mapOf("com.example.a" to PlanTarget(version = "2.0.0"))),
            repos,
            API + ("com.example.a" to Version.parse("1.0.0")),
            mapOf("com.example.a" to PluginSource(repo = REPO_A, held = true)),
        )

        assertEquals(Version.parse("2.0.0"), plan.actions.single().version)
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
