package io.github.revenge.xposed.tweaks.plugins.repos

import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange

/**
 * What to install and from where.
 */
internal data class InstallPlan(
    val actions: List<PlannedAction>,
    /** Non-blocking problems (skipped optionals, dependent-range conflicts). */
    val warnings: List<String>,
)

/** One plugin to download and install. One action = one artifact. */
internal data class PlannedAction(
    val id: String,
    val version: Version,
    /** Absolute artifact URL from the index. */
    val url: String,
    /** Expected artifact digest. Verified after download, before anything is applied. */
    val sha256: String,
    val size: Long,
    /** The repository this action installs from (recorded as provenance on success). */
    val repo: String,
    /** The installed version being replaced, or `null` for a fresh install. */
    val replaces: Version?,
)

/** A request to install (or update/downgrade) one root plugin. */
internal data class ResolveRequest(
    val id: String,
    /** Exact version to install; overrides [channel]. `null` = follow the channel. */
    val version: String? = null,
    val channel: String = REPO_CHANNEL_LATEST,
)

internal class ResolveException(message: String) : Exception(message)

/**
 * Resolves [request] against the given enabled repositories by priority order.
 *
 * - An installed plugin with known provenance only resolves from its pinned repository.
 *   New plugins resolve from the first repository that serves them.
 * - Dependency ranges are checked with [VersionRange.satisfies].
 * - Exact requested version > channel pointer > newest non-labeled version > newest version
 * - Unsatisfied required dependencies are planned recursively. Unresolvable ones abort with [ResolveException].
 * - Unresolvable optional dependencies produce a warning.
 * - A planned version that breaks an installed dependent's range produces a warning.
 *
 * [installed] must include internal plugins.
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

    /** Repositories allowed to serve [id]. The pinned one for installed plugins, else all by priority. */
    fun reposFor(id: String): List<Pair<String, RepoIndex>> {
        val pinned = if (id in installed) sources[id]?.repo else null
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

    fun plan(id: String, chosen: Triple<String, Version, RepoVersion>) {
        val (repoUrl, version, entry) = chosen
        actions[id] = PlannedAction(
            id = id,
            version = version,
            url = entry.url,
            sha256 = entry.sha256,
            size = entry.size,
            repo = repoUrl,
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
            val effective = actions[depId]?.version ?: installed[depId]

            if (effective != null && range.satisfies(effective)) continue
            if (depId in actions) {
                // Already planned but two dependents disagree.
                warnings += "Planned $depId@${actions[depId]!!.version} does not satisfy $id@$version (requires $range)"
                continue
            }

            val depChoice = select(candidatesFor(depId).filter { range.satisfies(it.second) })
            when {
                depChoice != null -> plan(depId, depChoice)

                dep.optional -> warnings +=
                    "Optional dependency '$depId' of $id@$version is unavailable (requires $range); skipped"

                effective != null -> throw ResolveException(
                    "Dependency '$depId' of $id@$version is installed at $effective but no version satisfying $range is available"
                )

                else -> throw ResolveException(
                    "Required dependency '$depId' of $id@$version is not installed and not available from any repository (requires $range)"
                )
            }
        }
    }

    // Resolve the root.
    val rootCandidates = candidatesFor(request.id)
    val rootChoice = if (request.version != null) {
        val exact = Version.parse(request.version)
        rootCandidates.firstOrNull { it.second == exact }
            ?: throw ResolveException("Version ${request.version} of '${request.id}' is not available")
    } else {
        // Channel pointer first (from the highest-priority repo defining it), then newest.
        reposFor(request.id).firstNotNullOfOrNull { (repoUrl, index) ->
            val plugin = index.plugins[request.id] ?: return@firstNotNullOfOrNull null
            val target = plugin.channels[request.channel] ?: return@firstNotNullOfOrNull null
            val version = runCatching { Version.parse(target) }.getOrNull() ?: return@firstNotNullOfOrNull null
            plugin.versions[target]?.let { Triple(repoUrl, version, it) }
        } ?: select(rootCandidates)
        ?: throw ResolveException("Plugin '${request.id}' is not available from any repository")
    }

    installed[request.id]?.let { current ->
        if (current == rootChoice.second) {
            // Nothing to do for the root; still return a valid (possibly empty) plan.
            return InstallPlan(emptyList(), listOf("'${request.id}' is already at $current"))
        }
        if (rootChoice.second < current) {
            warnings += "Downgrading '${request.id}' from $current to ${rootChoice.second}"
        }
    }

    plan(request.id, rootChoice)

    return InstallPlan(actions.values.toList(), warnings)
}
