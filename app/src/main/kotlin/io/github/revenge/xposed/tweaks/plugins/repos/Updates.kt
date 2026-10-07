package io.github.revenge.xposed.tweaks.plugins.repos

import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange

/** Available update of an installed plugin, resolved like an update install. */
internal data class PluginUpdate(
    val id: String,
    /** Installed version, or the pending on-disk update. */
    val installed: Version,
    val available: Version,
    val channel: String,
    val repo: String,
    /** Artifact size of [available] in bytes. */
    val size: Long,
    /** Other planned actions, such as dependency updates. Empty with a [blocker]. */
    val includes: List<PlannedAction>,
    val warnings: List<ResolveIssue>,
    /** Why the update can't install. */
    val blocker: ResolveIssue?,
)

/**
 * Lists updates of plugins installed from [repos].
 *
 * - An update exists when the followed channel points past the installed or [pending] version,
 *   or the same version got republished with another artifact.
 * - Held plugins get a [ResolveIssue.Held] blocker. A held dependency outside a new version's range blocks too.
 * - Each update resolves with [ResolveRequest.skipMissingOptionals]. A failure becomes [PluginUpdate.blocker].
 * - [ResolveIssue.Breaks] gets [ResolveIssue.Breaks.fixedBy] when the broken dependent has an unblocked update
 *   accepting the planned version.
 */
internal fun findUpdates(
    repos: List<Pair<String, RepoIndex>>,
    installed: Map<String, Version>,
    pending: Map<String, Version>,
    sources: Map<String, PluginSource>,
    installedDependencies: Map<String, Map<String, VersionRange>>,
): List<PluginUpdate> {
    val indexes = repos.toMap()
    val entries = HashMap<String, RepoVersion>()

    val updates = buildList {
        for ((id, source) in sources) {
            val repo = source.repo ?: continue
            // Internal plugins update with the loader itself, never through repos.
            if (repo == INTERNAL_REPO_URL) continue

            val installedVersion = installed[id] ?: continue
            val plugin = indexes[repo]?.plugins?.get(id) ?: continue
            val target = plugin.channels[source.channel] ?: continue
            val available = runCatching { Version.parse(target) }.getOrNull() ?: continue
            val entry = plugin.versions[target] ?: continue

            // Compare against a pending on-disk update if there is one, so it isn't re-offered.
            val current = pending[id] ?: installedVersion
            val republished = available.compareTo(current) == 0 &&
                    source.hash != null && entry.sha256 != source.hash
            if (!(available > current || republished)) continue

            val (plan, blocker) = if (source.held) null to ResolveIssue.Held(id, current) else try {
                resolveInstall(
                    ResolveRequest(id, skipMissingOptionals = true),
                    repos,
                    installed,
                    sources,
                    installedDependencies,
                ) to null
            } catch (e: PluginSystemResolveException) {
                null to e.issue
            }

            entries[id] = entry
            add(
                PluginUpdate(
                    id = id,
                    installed = current,
                    available = available,
                    channel = source.channel,
                    repo = repo,
                    size = entry.size,
                    includes = plan?.actions.orEmpty().filter { it.id != id },
                    warnings = plan?.warnings.orEmpty(),
                    blocker = blocker,
                )
            )
        }
    }

    val unblocked = updates.filter { it.blocker == null }.associateBy { it.id }

    /** Whether the update of [dependent] accepts [version] of [id]. */
    fun acceptedByUpdate(dependent: String, id: String, version: Version): Boolean {
        if (dependent !in unblocked) return false
        // A dropped dependency accepts anything.
        val dep = entries.getValue(dependent).dependencies[id] ?: return true
        return (dep.version?.let(VersionRange::parse) ?: VersionRange.ANY).satisfies(version)
    }

    return updates.map { update ->
        update.copy(
            warnings = update.warnings.map { issue ->
                if (issue is ResolveIssue.Breaks && acceptedByUpdate(issue.dependent, issue.id, issue.version))
                    issue.copy(fixedBy = issue.dependent)
                else issue
            },
        )
    }
}
