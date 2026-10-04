package io.github.revenge.xposed.tweaks.plugins.repos

import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange
import io.github.revenge.xposed.tweaks.plugins.PluginErrorCodes
import io.github.revenge.xposed.tweaks.plugins.PluginSystemError

internal data class InstallPlan(
    val actions: List<PlannedAction>,
    /** Non-blocking problems (skipped optionals, dependent-range conflicts). */
    val warnings: List<String>,
)

/** One plugin to download and install. */
internal data class PlannedAction(
    val id: String,
    val version: Version,
    /** Absolute artifact URL from the index. */
    val url: String,
    /** Expected artifact digest. Verified after download. */
    val sha256: String,
    val size: Long,
    /** The repository this action installs from (recorded as provenance on success). */
    val repo: String,
    /** The channel recorded for future updates. */
    val channel: String,
    /** `true` holds updates, `false` resumes them, `null` keeps the current value. */
    val hold: Boolean?,
    /** The installed version being replaced, or `null` for a fresh install. */
    val replaces: Version?,
    /** Dependents that pulled this plugin in. Empty for the root. */
    val dependents: Map<String, PlanDependent> = emptyMap(),
    /** Map<Repo, Map<Version, List<IncompatiblePluginId>>> */
    val allowed: Map<String, Map<String, List<String>>> = emptyMap(),
)

/** How a planned dependent depends on an action. */
internal data class PlanDependent(
    /** Whether it only depends on it optionally. */
    val optional: Boolean,
    val range: VersionRange,
)

/** Resolution rules. Anything unset uses defaults. */
internal data class PlanTarget(
    /** Resolve from this repository instead of its provenance or the priority order. */
    val repo: String? = null,
    /** Exact version, held once installed. Overrides [channel]. */
    val version: String? = null,
    /** Channel to follow. `null` = the installed plugin's channel, or [REPO_CHANNEL_LATEST]. */
    val channel: String? = null,
)

/** A request to install (or update/downgrade) one root plugin. */
internal data class ResolveRequest(
    val id: String,
    /** Per-plugin targets keyed by ID. `targets[id]` is the root's. */
    val targets: Map<String, PlanTarget> = emptyMap(),
)

internal class PluginSystemResolveException(message: String) :
    PluginSystemError(PluginErrorCodes.RESOLVE_FAILED, message)

/**
 * Resolves [request] against the given [repos] by priority order.
 * [installed] must include internal plugins.
 *
 * - A plugin with provenance only resolves from its pinned repository.
 *   New plugins resolve from the first repository that serves them. A [PlanTarget]'s repository overrides all.
 * - Dependency ranges are checked with [VersionRange.satisfies], targeted versions included.
 * - Root: exact targeted version > channel pointer > newest non-labeled version > newest version.
 * - Dependencies: exact targeted version > targeted channel pointer > newest non-labeled satisfying version.
 *   If a target can't satisfy its dependent, as an optional dependency, it'll skip with a warning, and fails as a required one.
 * - A plugin already at the chosen version is still planned when its source would change (repository, channel,
 *   hold, or a known artifact hash).
 * - A held dependency stays at its installed version unless targeted. If that doesn't satisfy the dependent,
 *   resolution fails.
 * - Unsatisfied required dependencies are planned recursively. Unresolvables will cause [PluginSystemResolveException].
 * - Unresolvable optional dependencies produce a warning.
 * - A planned version that breaks an installed dependent's range produces a warning.
 * - Targets for plugins outside the plan are ignored, so one set of targets can serve several plans.
 */
internal fun resolveInstall(
    request: ResolveRequest,
    repos: List<Pair<String, RepoIndex>>,
    installed: Map<String, Version>,
    sources: Map<String, PluginSource>,
    /** Installed plugins' dependency ranges (`dependent id -> dep id -> range`), for conflict warnings. */
    installedDependencies: Map<String, Map<String, VersionRange>> = emptyMap(),
): InstallPlan {
    val warnings = mutableListOf<String>()
    val actions = LinkedHashMap<String, PlannedAction>()

    /** `dependency id -> dependent id -> how`, for [PlannedAction.dependents]. */
    val edges = HashMap<String, MutableMap<String, PlanDependent>>()

    /** Records that [dependent] needs the planned [depId]. */
    fun link(depId: String, dependent: String, optional: Boolean, range: VersionRange) {
        edges.getOrPut(depId) { mutableMapOf() }[dependent] = PlanDependent(optional, range)
    }

    fun channelOf(id: String) = request.targets[id]?.channel ?: sources[id]?.channel ?: REPO_CHANNEL_LATEST

    fun holdOf(id: String) = request.targets[id]?.let { it.version != null }

    /** Repositories allowed to serve [id]. The targeted or pinned one, else all by priority. */
    fun reposFor(id: String): List<Pair<String, RepoIndex>> {
        val pinned = request.targets[id]?.repo ?: if (id in installed) sources[id]?.repo else null
        return if (pinned != null) repos.filter { (url, _) -> url == pinned } else repos
    }

    fun candidatesFor(id: String): List<Triple<String, Version, RepoVersion>> =
        reposFor(id).flatMap { (repoUrl, index) ->
            index.plugins[id]?.versions.orEmpty().mapNotNull { (key, entry) ->
                runCatching { Version.parse(key) }.getOrNull()?.let { Triple(repoUrl, it, entry) }
            }
        }

    /** Newest non-labeled, or newest, from the highest-priority repo serving that version. */
    fun select(candidates: List<Triple<String, Version, RepoVersion>>): Triple<String, Version, RepoVersion>? {
        if (candidates.isEmpty()) return null
        val best = candidates.filter { it.second.label == null }.maxByOrNull { it.second }
            ?: candidates.maxByOrNull { it.second }!!
        return candidates.first { it.second == best.second }
    }

    /** What [channel] points to, from the highest-priority repo defining it. */
    fun pointerOf(id: String, channel: String) = reposFor(id).firstNotNullOfOrNull { (repoUrl, index) ->
        val plugin = index.plugins[id] ?: return@firstNotNullOfOrNull null
        val target = plugin.channels[channel] ?: return@firstNotNullOfOrNull null
        val version = runCatching { Version.parse(target) }.getOrNull() ?: return@firstNotNullOfOrNull null
        plugin.versions[target]?.let { Triple(repoUrl, version, it) }
    }

    /** Whether installing [choice] would change nothing about the installed [id]. */
    fun unchanged(id: String, choice: Triple<String, Version, RepoVersion>): Boolean {
        val source = sources[id] ?: return false
        val hold = holdOf(id)
        return installed[id] == choice.second &&
                source.repo == choice.first &&
                source.channel == channelOf(id) &&
                // Unknown hashes (installed before they were recorded) count as the same artifact.
                (source.hash == null || source.hash == choice.third.sha256) &&
                (hold == null || source.held == hold)
    }

    /** Resolves a targeted dependency within [range]. Returns the choice, or why the target can't be met. */
    fun chooseTargeted(
        id: String,
        target: PlanTarget,
        range: VersionRange,
    ): Pair<Triple<String, Version, RepoVersion>?, String?> {
        val choice = when {
            target.version != null -> {
                val exact = Version.parse(target.version)
                candidatesFor(id).firstOrNull { it.second == exact }
                    ?: return null to "Version ${target.version} of '$id' is not available"
            }

            target.channel != null -> pointerOf(id, target.channel)
                ?: return null to "Channel '${target.channel}' of '$id' is not available"

            else -> select(candidatesFor(id).filter { range.satisfies(it.second) })
                ?: return null to "No version of '$id' satisfying $range is available"
        }

        if (!range.satisfies(choice.second)) return null to "'$id'@${choice.second} does not satisfy $range"
        return choice to null
    }

    fun plan(id: String, chosen: Triple<String, Version, RepoVersion>) {
        val (repoUrl, version, entry) = chosen
        actions[id] = PlannedAction(
            id = id,
            version = version,
            url = entry.url,
            sha256 = entry.sha256,
            size = entry.size,
            repo = repoUrl,
            channel = channelOf(id),
            hold = holdOf(id),
            replaces = installed[id],
        )

        // Does this version break any installed dependent?
        for ((dependent, deps) in installedDependencies) {
            val range = deps[id] ?: continue
            if (!range.satisfies(version)) {
                warnings += "Installing $id@$version does not satisfy '$dependent' (requires ${range})"
            }
        }

        for ((depId, dep) in entry.dependencies) {
            val range = dep.version?.let(VersionRange::parse) ?: VersionRange.ANY
            val target = request.targets[depId]

            val planned = actions[depId]
            if (planned != null) {
                if (!range.satisfies(planned.version)) {
                    // Already planned but two dependents disagree.
                    warnings += "Planned $depId@${planned.version} does not satisfy $id@$version (requires $range)"
                }
                link(depId, id, dep.optional, range)
                continue
            }

            val effective = installed[depId]
            // Installed and fine, nothing to do unless the user targeted it
            if (target == null && effective != null && range.satisfies(effective)) continue

            val held = target == null && sources[depId]?.held == true
            val (depChoice, problem) = when {
                held -> null to null
                target != null -> chooseTargeted(depId, target, range)
                else -> select(candidatesFor(depId).filter { range.satisfies(it.second) }) to null
            }

            when {
                depChoice != null -> {
                    if (effective != null && unchanged(depId, depChoice)) continue
                    plan(depId, depChoice)
                    link(depId, id, dep.optional, range)
                }

                dep.optional -> warnings += problem?.let { "$it; skipped optional dependency of $id@$version" }
                    ?: "Optional dependency '$depId' of $id@$version is unavailable (requires $range); skipped"

                problem != null -> throw PluginSystemResolveException("$problem, required by $id@$version")

                held -> throw PluginSystemResolveException(
                    "Dependency '$depId' is held at $effective, which does not satisfy $id@$version " +
                            "(requires $range); unhold '$depId' to let it update"
                )

                effective != null -> throw PluginSystemResolveException(
                    "Dependency '$depId' of $id@$version is installed at $effective but no version satisfying $range is available"
                )

                else -> throw PluginSystemResolveException(
                    "Required dependency '$depId' of $id@$version is not installed and not available from any repository (requires $range)"
                )
            }
        }
    }

    // Resolve the root.
    val rootTarget = request.targets[request.id]

    val rootChoice = if (rootTarget?.version != null) {
        val exact = Version.parse(rootTarget.version)
        candidatesFor(request.id).firstOrNull { it.second == exact }
            ?: throw PluginSystemResolveException("Version ${rootTarget.version} of '${request.id}' is not available")
    } else {
        pointerOf(request.id, channelOf(request.id))
            ?: select(candidatesFor(request.id))
            ?: throw PluginSystemResolveException("Plugin '${request.id}' is not available from any repository")
    }

    installed[request.id]?.let { current ->
        if (unchanged(request.id, rootChoice))
            return InstallPlan(emptyList(), listOf("'${request.id}' is already at $current"))
        if (rootChoice.second < current) {
            warnings += "Downgrading '${request.id}' from $current to ${rootChoice.second}"
        }
    }

    plan(request.id, rootChoice)

    /** Installed plugins outside the plan whose range [version] of [id] breaks. */
    fun breaks(id: String, version: Version) = installedDependencies
        .filter { (dependent, deps) -> dependent !in actions && deps[id]?.satisfies(version) == false }
        .keys.toList()

    return InstallPlan(
        actions.values.map { action ->
            val dependents = edges[action.id].orEmpty()
            val allowed = repos.mapNotNull { (repoUrl, index) ->
                val plugin = index.plugins[action.id] ?: return@mapNotNull null
                repoUrl to plugin.versions.keys.mapNotNull { key ->
                    val version = runCatching { Version.parse(key) }.getOrNull() ?: return@mapNotNull null
                    if (dependents.values.all { it.range.satisfies(version) }) key to breaks(action.id, version)
                    else null
                }.toMap()
            }.toMap()

            action.copy(dependents = dependents, allowed = allowed)
        },
        warnings,
    )
}
