package io.github.revenge.xposed.tweaks.plugins.repos

import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange
import io.github.revenge.xposed.tweaks.plugins.PluginErrorCodes
import io.github.revenge.xposed.tweaks.plugins.PluginSystemError

internal data class InstallPlan(
    val actions: List<PlannedAction>,
    /** Non-blocking problems (skipped optionals, dependent-range conflicts). */
    val warnings: List<ResolveIssue>,
)

/** Why a dependency can't be resolved. */
internal enum class UnresolvedReason(val js: String) {
    /** No satisfying version in any allowed repository. */
    UNAVAILABLE("unavailable"),

    /** Held at an installed version outside the range. */
    HELD("held"),

    /** Targeted version or channel can't be met. */
    TARGET("target"),
}

/** Resolution problem, as a plan warning or the cause of a [PluginSystemResolveException]. */
internal sealed class ResolveIssue {
    abstract val message: String

    protected abstract fun fields(): Map<String, Any?>

    fun toJSPayload(): Map<String, Any?> = fields() + ("message" to message)

    /** Root already at the resolved [version]. Comes with an empty plan. */
    data class UpToDate(val id: String, val version: Version) : ResolveIssue() {
        override val message get() = "'$id' is already at $version"
        override fun fields() = mapOf("type" to "upToDate", "id" to id, "version" to version.toString())
    }

    data class Downgrade(val id: String, val from: Version, val to: Version) : ResolveIssue() {
        override val message get() = "Downgrading '$id' from $from to $to"
        override fun fields() =
            mapOf("type" to "downgrade", "id" to id, "from" to from.toString(), "to" to to.toString())
    }

    /**
     * Planned [version] of [id] outside the [range] of installed [dependent], outside the plan.
     * [fixedBy] names an update of [dependent] accepting [version], set by [findUpdates].
     */
    data class Breaks(
        val id: String,
        val version: Version,
        val dependent: String,
        val range: VersionRange,
        val fixedBy: String? = null,
    ) : ResolveIssue() {
        override val message get() = "Installing $id@$version does not satisfy '$dependent' (requires $range)"
        override fun fields() = mapOf(
            "type" to "breaks",
            "id" to id,
            "version" to version.toString(),
            "dependent" to dependent,
            "range" to range.toString(),
            "fixedBy" to fixedBy,
        )
    }

    /** Planned [version] of [id] outside the [range] of planned [dependent]. */
    data class Conflict(
        val id: String,
        val version: Version,
        val dependent: String,
        val dependentVersion: Version,
        val range: VersionRange,
    ) : ResolveIssue() {
        override val message get() = "Planned $id@$version does not satisfy $dependent@$dependentVersion (requires $range)"
        override fun fields() = mapOf(
            "type" to "conflict",
            "id" to id,
            "version" to version.toString(),
            "dependent" to dependent,
            "dependentVersion" to dependentVersion.toString(),
            "range" to range.toString(),
        )
    }

    /**
     * Dependency [id] of [dependent] with no usable version. Skipped when [optional], blocks otherwise.
     * [installed] is the installed version, if any. [detail] explains a [UnresolvedReason.TARGET].
     */
    data class Unresolved(
        val id: String,
        val dependent: String,
        val dependentVersion: Version,
        val range: VersionRange,
        val optional: Boolean,
        val reason: UnresolvedReason,
        val installed: Version? = null,
        val detail: String? = null,
    ) : ResolveIssue() {
        override val message
            get() = when {
                reason == UnresolvedReason.TARGET && optional ->
                    "$detail; skipped optional dependency of $dependent@$dependentVersion"

                reason == UnresolvedReason.TARGET -> "$detail, required by $dependent@$dependentVersion"

                reason == UnresolvedReason.HELD && optional ->
                    "Optional dependency '$id' of $dependent@$dependentVersion is held at $installed (requires $range); skipped"

                reason == UnresolvedReason.HELD ->
                    "Dependency '$id' is held at $installed, which does not satisfy $dependent@$dependentVersion " +
                            "(requires $range); unhold '$id' to let it update"

                optional -> "Optional dependency '$id' of $dependent@$dependentVersion is unavailable (requires $range); skipped"

                installed != null ->
                    "Dependency '$id' of $dependent@$dependentVersion is installed at $installed but no version satisfying $range is available"

                else ->
                    "Required dependency '$id' of $dependent@$dependentVersion is not installed and not available from any repository (requires $range)"
            }

        override fun fields() = mapOf(
            "type" to "unresolved",
            "id" to id,
            "dependent" to dependent,
            "dependentVersion" to dependentVersion.toString(),
            "range" to range.toString(),
            "optional" to optional,
            "reason" to reason.js,
            "installed" to installed?.toString(),
            "detail" to detail,
        )
    }

    /** Paused updates of [id], held at [version]. */
    data class Held(val id: String, val version: Version) : ResolveIssue() {
        override val message get() = "Updates of '$id' are paused at $version"
        override fun fields() = mapOf("type" to "held", "id" to id, "version" to version.toString())
    }

    /** Root [id] not served, or not at the requested [version]. */
    data class Unavailable(val id: String, val version: String? = null) : ResolveIssue() {
        override val message
            get() = if (version != null) "Version $version of '$id' is not available"
            else "Plugin '$id' is not available from any repository"

        override fun fields() = mapOf("type" to "unavailable", "id" to id, "version" to version)
    }
}

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
    /** Versions satisfying planned dependents keyed by repository with metadata. */
    val candidates: Map<String, Map<String, VersionCandidate>> = emptyMap(),
)

/** A version a planned plugin could switch to. */
internal data class VersionCandidate(
    /** Installed plugins outside the plan this version breaks. */
    val breaks: List<String>,
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
    /** Skips untargeted optional dependencies that aren't installed, eg. for updates. */
    val skipMissingOptionals: Boolean = false,
)

internal class PluginSystemResolveException(val issue: ResolveIssue) :
    PluginSystemError(PluginErrorCodes.RESOLVE_FAILED, issue.message, details = issue.toJSPayload())

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
 * - With [ResolveRequest.skipMissingOptionals], untargeted optional dependencies that aren't installed are skipped silently.
 * - A planned version that breaks an installed dependent outside the plan produces a warning.
 * - A planned version outside a planned dependent's range produces a warning.
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
    val warnings = mutableListOf<ResolveIssue>()
    val actions = LinkedHashMap<String, PlannedAction>()

    /** `planned id -> dep id -> range` of planned versions, for conflict warnings. */
    val plannedDependencies = HashMap<String, Map<String, VersionRange>>()

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
        plannedDependencies[id] = entry.dependencies.mapValues { (_, dep) ->
            dep.version?.let(VersionRange::parse) ?: VersionRange.ANY
        }

        for ((depId, dep) in entry.dependencies) {
            val range = plannedDependencies.getValue(id).getValue(depId)
            val target = request.targets[depId]

            // Already planned. Disagreeing ranges warn after planning.
            if (depId in actions) {
                link(depId, id, dep.optional, range)
                continue
            }

            val effective = installed[depId]
            // Installed and fine, nothing to do unless the user targeted it
            if (target == null && effective != null && range.satisfies(effective)) continue
            // The user left it out
            if (target == null && effective == null && dep.optional && request.skipMissingOptionals) continue

            val held = target == null && sources[depId]?.held == true
            val (depChoice, problem) = when {
                held -> null to null
                target != null -> chooseTargeted(depId, target, range)
                else -> select(candidatesFor(depId).filter { range.satisfies(it.second) }) to null
            }

            if (depChoice != null) {
                if (effective != null && unchanged(depId, depChoice)) continue
                plan(depId, depChoice)
                link(depId, id, dep.optional, range)
                continue
            }

            val issue = ResolveIssue.Unresolved(
                id = depId,
                dependent = id,
                dependentVersion = version,
                range = range,
                optional = dep.optional,
                reason = when {
                    problem != null -> UnresolvedReason.TARGET
                    held -> UnresolvedReason.HELD
                    else -> UnresolvedReason.UNAVAILABLE
                },
                installed = effective,
                detail = problem,
            )

            if (dep.optional) warnings += issue
            else throw PluginSystemResolveException(issue)
        }
    }

    // Resolve the root.
    val rootTarget = request.targets[request.id]

    val rootChoice = if (rootTarget?.version != null) {
        val exact = Version.parse(rootTarget.version)
        candidatesFor(request.id).firstOrNull { it.second == exact }
            ?: throw PluginSystemResolveException(ResolveIssue.Unavailable(request.id, rootTarget.version))
    } else {
        pointerOf(request.id, channelOf(request.id))
            ?: select(candidatesFor(request.id))
            ?: throw PluginSystemResolveException(ResolveIssue.Unavailable(request.id))
    }

    installed[request.id]?.let { current ->
        if (unchanged(request.id, rootChoice))
            return InstallPlan(emptyList(), listOf(ResolveIssue.UpToDate(request.id, current)))
        if (rootChoice.second < current) {
            warnings += ResolveIssue.Downgrade(request.id, current, rootChoice.second)
        }
    }

    plan(request.id, rootChoice)

    // After planning, so dependents replaced by the plan never count as broken.
    for (action in actions.values) {
        for ((dependent, deps) in installedDependencies) {
            if (dependent in actions) continue
            val range = deps[action.id] ?: continue
            if (!range.satisfies(action.version))
                warnings += ResolveIssue.Breaks(action.id, action.version, dependent, range)
        }

        for ((dependent, deps) in plannedDependencies) {
            val range = deps[action.id] ?: continue
            if (!range.satisfies(action.version))
                warnings += ResolveIssue.Conflict(
                    action.id,
                    action.version,
                    dependent,
                    actions.getValue(dependent).version,
                    range,
                )
        }
    }

    /** Installed plugins outside the plan whose range [version] of [id] breaks. */
    fun breaks(id: String, version: Version) = installedDependencies
        .filter { (dependent, deps) -> dependent !in actions && deps[id]?.satisfies(version) == false }
        .keys.toList()

    return InstallPlan(
        actions.values.map { action ->
            val dependents = edges[action.id].orEmpty()
            val candidates = repos.mapNotNull { (repoUrl, index) ->
                val plugin = index.plugins[action.id] ?: return@mapNotNull null
                repoUrl to plugin.versions.keys.mapNotNull { key ->
                    val version = runCatching { Version.parse(key) }.getOrNull() ?: return@mapNotNull null
                    if (dependents.values.all { it.range.satisfies(version) }) key to VersionCandidate(
                        breaks(
                            action.id,
                            version
                        )
                    )
                    else null
                }.toMap()
            }.toMap()

            action.copy(dependents = dependents, candidates = candidates)
        },
        warnings,
    )
}
