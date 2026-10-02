package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.plugins.PluginDependency
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginDependencyGraphTest {

    private fun manifest(
        id: String,
        version: String = "1.0",
        vararg dependencies: Pair<String, PluginDependency>,
    ) = PluginManifest(
        id = id,
        name = id,
        description = "",
        author = "",
        dependencies = dependencies.toMap(),
        version = Version.parse(version),
    )

    private fun dep(range: String = "*", optional: Boolean = false) =
        PluginDependency(VersionRange.parse(range), optional)

    private fun graphOf(vararg manifests: PluginManifest) =
        PluginDependencyGraph(manifests.associateBy { it.id })

    // region unsatisfiedOptionalDependencies

    @Test
    fun `an out-of-range optional dependency is unsatisfied`() {
        val graph = graphOf(
            manifest("a", dependencies = arrayOf("b" to dep(">=2.0", optional = true))),
            manifest("b", version = "1.0"),
        )

        assertEquals(setOf("b"), graph.unsatisfiedOptionalDependencies("a"))
    }

    @Test
    fun `an out-of-range required dependency is not reported`() {
        val graph = graphOf(
            manifest("a", dependencies = arrayOf("b" to dep(">=2.0"))),
            manifest("b", version = "1.0"),
        )

        assertEquals(emptySet(), graph.unsatisfiedOptionalDependencies("a"))
        assertEquals(PluginErrorCodes.DEPENDENCY_UNSATISFIED, graph.problem("a", "b")?.code)
    }

    @Test
    fun `a dependency the graph has never heard of is not unsatisfied`() {
        val graph = graphOf(
            manifest(
                "a",
                dependencies = arrayOf(
                    "revenge.settings" to dep(optional = true),
                    "gone" to dep(),
                ),
            ),
        )

        assertEquals(emptySet(), graph.unsatisfiedOptionalDependencies("a"))
        // ...while the graph itself still knows they cannot be linked.
        assertEquals(PluginErrorCodes.DEPENDENCY_MISSING, graph.problem("a", "revenge.settings")?.code)
    }

    // endregion

    // region problem

    @Test
    fun `an absent dependency is missing`() {
        val graph = graphOf(manifest("a", dependencies = arrayOf("b" to dep())))

        val problem = graph.problem("a", "b")
        assertEquals(PluginErrorCodes.DEPENDENCY_MISSING, problem?.code)
    }

    @Test
    fun `an out-of-range dependency is unsatisfied, not missing`() {
        val graph = graphOf(
            manifest("a", dependencies = arrayOf("b" to dep(">=2.0"))),
            manifest("b", version = "1.0"),
        )

        val problem = graph.problem("a", "b")
        assertEquals(PluginErrorCodes.DEPENDENCY_UNSATISFIED, problem?.code)
    }

    @Test
    fun `an in-range dependency has no problem`() {
        val graph = graphOf(
            manifest("a", dependencies = arrayOf("b" to dep(">=1.0"))),
            manifest("b", version = "1.5"),
        )

        assertNull(graph.problem("a", "b"))
    }

    @Test
    fun `an edge that does not exist is not a problem`() {
        val graph = graphOf(manifest("a"), manifest("b"))

        assertNull(graph.problem("a", "b"), "a never asked for b")
    }

    // endregion

    // region satisfiedDependencies

    @Test
    fun `satisfied dependencies exclude the out-of-range, optional or not`() {
        val graph = graphOf(
            manifest(
                "a",
                dependencies = arrayOf(
                    "ok" to dep(">=1.0"),
                    "old" to dep(">=2.0"),
                    "okOptional" to dep(">=1.0", optional = true),
                    "oldOptional" to dep(">=2.0", optional = true),
                ),
            ),
            manifest("ok", version = "1.0"),
            manifest("old", version = "1.0"),
            manifest("okOptional", version = "1.0"),
            manifest("oldOptional", version = "1.0"),
        )

        assertEquals(setOf("ok", "okOptional"), graph.satisfiedDependencies("a"))
    }

    // endregion

    // region requiredDependents

    @Test
    fun `required dependents are transitive and exclude the root`() {
        val graph = graphOf(
            manifest("base"),
            manifest("mid", dependencies = arrayOf("base" to dep())),
            manifest("leaf", dependencies = arrayOf("mid" to dep())),
            manifest("unrelated"),
        )

        assertEquals(setOf("mid", "leaf"), graph.requiredDependents("base"))
    }

    @Test
    fun `optional dependents are never required dependents`() {
        val graph = graphOf(
            manifest("base"),
            manifest("optional", dependencies = arrayOf("base" to dep(optional = true))),
            // Depends on the optional dependent, so it must not be pulled in transitively either.
            manifest("beyond", dependencies = arrayOf("optional" to dep())),
        )

        assertTrue(graph.requiredDependents("base").isEmpty())
    }

    @Test
    fun `satisfied optional dependents skip the out-of-range`() {
        val graph = graphOf(
            manifest("base", version = "1.0"),
            manifest("ok", dependencies = arrayOf("base" to dep(">=1.0", optional = true))),
            manifest("tooOld", dependencies = arrayOf("base" to dep(">=2.0", optional = true))),
            manifest("required", dependencies = arrayOf("base" to dep())),
        )

        assertEquals(setOf("ok"), graph.satisfiedOptionalDependents("base"))
    }

    // endregion

    // region loadOrder

    @Test
    fun `dependencies are ordered before dependents`() {
        val order = graphOf(
            manifest("leaf", dependencies = arrayOf("mid" to dep())),
            manifest("mid", dependencies = arrayOf("base" to dep())),
            manifest("base"),
        ).loadOrder()

        assertContentEquals(listOf("base", "mid", "leaf"), order.ordered)
        assertTrue(order.dropped.isEmpty())
        assertTrue(order.cycles.isEmpty())
    }

    @Test
    fun `a missing required dependency drops the plugin and cascades`() {
        val order = graphOf(
            manifest("a", dependencies = arrayOf("absent" to dep())),
            manifest("b", dependencies = arrayOf("a" to dep())),
            manifest("fine"),
        ).loadOrder()

        assertEquals(setOf("a", "b"), order.dropped.keys)
        assertContentEquals(listOf("fine"), order.ordered)
        assertEquals(
            PluginErrorCodes.DEPENDENCY_MISSING,
            order.dropped["a"]?.single()?.code,
        )
        assertEquals(
            PluginErrorCodes.DEPENDENCY_MISSING,
            order.dropped["b"]?.single()?.code,
            "a was dropped, so from b's side it is simply absent",
        )
    }

    @Test
    fun `an optional dependency never drops the dependent`() {
        val order = graphOf(
            manifest("a", dependencies = arrayOf("absent" to dep(optional = true))),
        ).loadOrder()

        assertTrue(order.dropped.isEmpty())
        assertContentEquals(listOf("a"), order.ordered)
        assertTrue(
            order.satisfied["a"].orEmpty().isEmpty(),
            "it loads, but with nothing to link",
        )
    }

    @Test
    fun `an unsatisfied optional stops being an ordering edge`() {
        val order = graphOf(
            manifest("a", dependencies = arrayOf("b" to dep(">=2.0", optional = true))),
            manifest("b", version = "1.0"),
        ).loadOrder()

        // Both load; `a` does not have to wait for `b`, because it cannot link it.
        assertEquals(setOf("a", "b"), order.ordered.toSet())
        assertTrue(order.satisfied.getValue("a").isEmpty())
    }

    @Test
    fun `load order satisfied excludes a dropped dependency`() {
        val order = graphOf(
            // `dropper` is in range for `a`, but cannot load itself.
            manifest("dropper", dependencies = arrayOf("absent" to dep())),
            manifest("a", dependencies = arrayOf("dropper" to dep(optional = true))),
        ).loadOrder()

        assertEquals(setOf("dropper"), order.dropped.keys)
        assertContentEquals(listOf("a"), order.ordered)
        assertTrue(
            order.satisfied.getValue("a").isEmpty(),
            "a dropped dependency is not linkable, however well its version matched",
        )
    }

    @Test
    fun `a cycle is reported and ordered out`() {
        val order = graphOf(
            manifest("a", dependencies = arrayOf("b" to dep())),
            manifest("b", dependencies = arrayOf("a" to dep())),
            manifest("fine"),
        ).loadOrder()

        assertEquals(setOf("a", "b"), order.cycles)
        assertContentEquals(listOf("fine"), order.ordered)
        assertTrue(order.dropped.isEmpty(), "a cycle is not a dependency problem")
    }

    @Test
    fun `a plugin depending on itself still loads`() {
        val order = graphOf(manifest("a", dependencies = arrayOf("a" to dep()))).loadOrder()

        assertContentEquals(listOf("a"), order.ordered)
        assertTrue(order.cycles.isEmpty())
    }

    // endregion
}
