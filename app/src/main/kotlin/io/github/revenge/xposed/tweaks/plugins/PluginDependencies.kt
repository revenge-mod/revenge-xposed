package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.plugins.PluginDependency
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.Version

/**
 * Why a dependency cannot be satisfied.
 * 
 * @param code [PluginErrorCodes]
 */
internal class DependencyProblem(val code: String, val reason: String)

internal class LoadOrder(
    /** Plugin IDs in load order. */
    val ordered: List<String>,
    /** Plugins that cannot load, with the *required* dependency problems that stopped them. */
    val dropped: Map<String, List<DependencyProblem>>,
    /** Plugins in a dependency cycle. */
    val cycles: Set<String>,
    /**
     * The dependencies it a plugin may link.
     *
     * May have less entries than [PluginDependencyGraph.satisfiedDependencies]. An optional dependency may've failed at load time.
     */
    val satisfied: Map<String, Set<String>>,
)

/**
 * The plugin dependency graph.
 *
 * This only answers if *could* A link B (if B satisfies A), per the manifests as they are now.
 * 
 * You may be looking for [PluginFactory.chainedDependencies] instead, which is what actually chained into A's class loader.
 */
internal class PluginDependencyGraph(private val manifests: Map<String, PluginManifest>) {

    /** Whether [id] is known to the graph at all. */
    fun has(id: String): Boolean = id in manifests

    /** Why [dependentId] cannot link [depId] right now, or `null` when it can or isn't a dependent. */
    fun problem(dependentId: String, depId: String): DependencyProblem? {
        val dep = manifests[dependentId]?.dependencies?.get(depId) ?: return null
        return problemOf(depId, dep, manifests[depId]?.version)
    }

    /**
     * Dependencies of [id] that are present and in range, required and optional.
     *
     * If you're looking to determine plugin loading order, use [LoadOrder.satisfied].
     */
    fun satisfiedDependencies(id: String): Set<String> =
        dependenciesOf(id).filterKeys { depId -> problem(id, depId) == null }.keys

    /** Optional dependencies of [id] that are installed at a version outside the declared range. */
    fun unsatisfiedOptionalDependencies(id: String): Set<String> =
        dependenciesOf(id)
            .filterValues { it.optional }
            .filterKeys { depId -> has(depId) && problem(id, depId) != null }
            .keys

    fun requiredDependencies(id: String): Set<String> =
        dependenciesOf(id).filterValues { !it.optional }.keys

    /** Every plugin that requires [id], transitively. */
    fun requiredDependents(id: String): Set<String> {
        val found = mutableSetOf(id)

        var changed = true
        while (changed) {
            changed = false
            for ((dependentId, manifest) in manifests) {
                if (dependentId in found) continue
                val requiresDropped = manifest.dependencies.any { (depId, dep) ->
                    !dep.optional && depId in found
                }
                if (requiresDropped) {
                    found += dependentId
                    changed = true
                }
            }
        }

        return found - id
    }

    /** Direct dependents declaring [id] as optional whose satisfies. */
    fun satisfiedOptionalDependents(id: String): Set<String> = buildSet {
        for ((dependentId, manifest) in manifests) {
            val dep = manifest.dependencies[id] ?: continue
            if (!dep.optional) continue
            if (problem(dependentId, id) == null) add(dependentId)
        }
    }

    /**
     * Orders every plugin so dependencies load before dependents.
     *
     * Unsatisfiable required dependencies will drop the dependent, and dropping one can unsatisfy its dependents, causing a cascade.
     * Optional dependencies will not drop a dependent. They simply won't appear in [LoadOrder.satisfied].
     */
    fun loadOrder(): LoadOrder {
        val dropped = mutableMapOf<String, List<DependencyProblem>>()
        val satisfied = mutableMapOf<String, Set<String>>()

        var changed = true
        while (changed) {
            changed = false

            for ((id, manifest) in manifests) {
                if (id in dropped) continue

                val problems = mutableListOf<DependencyProblem>()
                val linkable = mutableSetOf<String>()

                for ((depId, dep) in manifest.dependencies) {
                    // A dropped dependency is considered absent.
                    val version = if (depId in dropped) null else manifests[depId]?.version
                    val problem = problemOf(depId, dep, version)

                    when {
                        problem == null -> linkable += depId
                        !dep.optional -> problems += problem
                    }
                }

                if (problems.isEmpty()) {
                    satisfied[id] = linkable
                } else {
                    dropped[id] = problems
                    satisfied -= id
                    changed = true
                }
            }
        }

        val remaining = manifests.keys.filterTo(mutableSetOf()) { it !in dropped }
        val ordered = mutableListOf<String>()
        val cycles = mutableSetOf<String>()

        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { id ->
                dependenciesOf(id).none { (depId, dep) ->
                    depId in remaining && depId != id &&
                            (!dep.optional || depId in satisfied[id].orEmpty())
                }
            }

            // Nothing can go next, so everything left points at something else left.
            if (ready.isEmpty()) {
                cycles += remaining
                break
            }

            ordered += ready
            remaining -= ready.toSet()
        }

        return LoadOrder(ordered, dropped, cycles, satisfied)
    }

    private fun dependenciesOf(id: String): Map<String, PluginDependency> =
        manifests[id]?.dependencies.orEmpty()

    /** @param version the dependency's installed version, or `null` when it is absent. */
    private fun problemOf(depId: String, dep: PluginDependency, version: Version?): DependencyProblem? = when {
        version == null -> DependencyProblem(
            PluginErrorCodes.DEPENDENCY_MISSING,
            "missing dependency '$depId'",
        )

        !dep.version.satisfies(version) -> DependencyProblem(
            PluginErrorCodes.DEPENDENCY_UNSATISFIED,
            "dependency '$depId' version $version does not satisfy '${dep.version}'",
        )

        else -> null
    }
}
