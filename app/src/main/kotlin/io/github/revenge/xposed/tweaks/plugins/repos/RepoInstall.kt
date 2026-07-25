package io.github.revenge.xposed.tweaks.plugins.repos

import io.github.revenge.Logger
import io.github.revenge.plugins.Version
import io.github.revenge.xposed.httpClient
import io.github.revenge.xposed.tweaks.plugins.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex

private const val DOWNLOAD_TIMEOUT = 60_000L

private const val REPO_INSTALL_TMP_PREFIX = ".repo-install-"

/**
 * Queue up concurrent `installFromRepo` calls. Each execution re-validates at dequeue time,
 * so overlapping plans' already-satisfied actions no-op and the end state equals sequential calls.
 */
internal val repoInstallMutex = Mutex()

internal class InstallPlanResult(
    /** Brand-new plugins to be loaded in order. */
    val fresh: List<PluginFactory>,
    /**
     * Actions applied on disk only (updates to installed IDs + fresh installs that need them).
     * Nothing running was touched, these can load at next boot.
     */
    val pending: List<RepoInstallAction>,
)

internal class RepoInstallAction(
    val id: String,
    val version: Version,
    val url: String,
    val sha256: String,
    val size: Long,
    /** Repository provenance recorded on success. */
    val repo: String,
    /** Channel followed for future update checks. */
    val channel: String,
)

internal class DownloadProgress(
    val action: RepoInstallAction,
    /** Bytes received so far. */
    val received: Long,
    /** 1-based position of this artifact in the plan. */
    val index: Int,
    /** Number of artifacts in the plan. */
    val count: Int,
)

internal fun parseRepoInstallAction(raw: Any?): RepoInstallAction {
    val map = raw as? Map<*, *> ?: throw Error("Expected a plan action object")
    fun string(key: String) = map[key] as? String ?: throw Error("Plan action is missing '$key'")
    return RepoInstallAction(
        id = string("id"),
        version = Version.parse(string("version")),
        url = string("url"),
        sha256 = string("sha256"),
        size = (map["size"] as? Number)?.toLong() ?: throw Error("Plan action is missing 'size'"),
        repo = string("repo"),
        channel = (map["channel"] as? String) ?: REPO_CHANNEL_LATEST,
    )
}

/**
 * Executes an install plan: download, verify, extract, then apply.
 *
 * - Every artifact's size and SHA-256 are verified against the plan before `dist/` is touched.
 * - Each artifact's `manifest.json` is then parsed and validated. A manifest whose ID or version differs
 *   from the plan aborts the whole plan with `dist/` untouched.
 * - The on-disk swap ([applyStagedPlugin]) never disturbs running code, all updates require a reload:
 *   - [isUpdate] IDs (already installed) are applied on disk only, see [InstallPlanResult.pending].
 *   - New IDs whose required deps are all live load immediately ([InstallPlanResult.fresh]),
 *     dependencies before dependents so class-loader chaining finds them.
 *   - New IDs requiring a pending dep (in-plan or [isPendingReload]) are deferred too,
 *     loading them against the running old dependency would link stale code.
 *
 * [knownVersions] must contain the currently registered plugin versions.
 * Planned versions are added on top so intra-plan dependencies validate.
 */
internal suspend fun executeInstallPlan(
    actions: List<RepoInstallAction>,
    dataDir: String,
    knownVersions: Map<String, Version>,
    isUpdate: (String) -> Boolean,
    isPendingReload: (String) -> Boolean,
    log: Logger,
    onProgress: (DownloadProgress) -> Unit = {},
): InstallPlanResult {
    if (actions.isEmpty()) return InstallPlanResult(emptyList(), emptyList())

    val root = externalPluginsRoot(dataDir).apply { mkdirs() }
    check(root.isDirectory && root.canWrite()) { "Plugin directory is not writable: $root" }

    val staged = mutableListOf<Pair<RepoInstallAction, StagedPlugin>>()
    try {
        // Download + verify + extract + manifest cross-check.
        for ((i, action) in actions.withIndex()) {
            val emit = throttledProgress(action, i + 1, actions.size, onProgress)
            emit(0)
            val bytes = downloadAndVerify(httpClient, action, emit)
            emit(action.size)

            val plugin = extractPluginZip(bytes.inputStream(), root, "$REPO_INSTALL_TMP_PREFIX$i")
            staged += action to plugin

            // The manifest is authoritative, any mismatch with the plan aborts the whole plan.
            if (plugin.manifest.id != action.id || Version.parse(plugin.manifest.version) != action.version) {
                throw PluginException(
                    PluginErrorCodes.INSTALL_MISMATCH,
                    "Artifact manifest (${plugin.manifest.id}@${plugin.manifest.version}) does not match " +
                            "the plan (${action.id}@${action.version}); aborting the install plan",
                )
            }
        }

        // Apply, dependencies before dependents
        val effectiveVersions = knownVersions + actions.associate { it.id to it.version }
        val fresh = mutableListOf<PluginFactory>()
        val pending = mutableListOf<RepoInstallAction>()
        val pendingIds = mutableSetOf<String>()
        for ((action, plugin) in orderStagedByDependencies(staged)) {
            val dir = applyStagedPlugin(plugin, root)
            runCatching {
                SourcesStore.set(action.id, PluginSource(repo = action.repo, channel = action.channel))
            }.onFailure { log.e("Failed to record plugin source for ${action.id}", it) }

            val deferred = isUpdate(action.id) || plugin.manifest.dependencies.any { (depId, dep) ->
                !dep.optional && (depId in pendingIds || isPendingReload(depId))
            }
            if (deferred) {
                pendingIds += action.id
                pending += action
                log.i("Applied ${action.id}@${action.version} from ${action.repo} (pending reload)")
            } else {
                fresh += readExternalPluginDir(dir, effectiveVersions, log)
                log.i("Installed ${action.id}@${action.version} from ${action.repo}")
            }
        }
        return InstallPlanResult(fresh, pending)
    } finally {
        for ((_, plugin) in staged) plugin.dir.deleteRecursively()
    }
}

/** Milliseconds between progress ticks per artifact. */
private const val PROGRESS_INTERVAL_MS = 150L

/**
 * The first (0 bytes) and final (size bytes) ticks always pass.
 * For in between, at most one tick per [PROGRESS_INTERVAL_MS] or per 10% received.
 */
private fun throttledProgress(
    action: RepoInstallAction,
    index: Int,
    count: Int,
    onProgress: (DownloadProgress) -> Unit,
): (Long) -> Unit {
    var lastTime = 0L
    var lastBytes = -1L
    val delta = (action.size / 10).coerceAtLeast(1)
    return { received ->
        val now = System.currentTimeMillis()
        if (received == 0L || received >= action.size ||
            now - lastTime >= PROGRESS_INTERVAL_MS || received - lastBytes >= delta
        ) {
            lastTime = now
            lastBytes = received
            onProgress(DownloadProgress(action, received, index, count))
        }
    }
}

/** Downloads one artifact and verifies its size and SHA-256 against the plan. */
private suspend fun downloadAndVerify(
    client: HttpClient,
    action: RepoInstallAction,
    onReceived: (Long) -> Unit,
): ByteArray {
    val response = client.get(action.url) {
        timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT }
        onDownload { received, _ -> onReceived(received) }
    }
    if (response.status != HttpStatusCode.OK) {
        throw PluginException(
            PluginErrorCodes.INSTALL_FAILED,
            "Failed to download ${action.id}@${action.version}: ${response.status}",
        )
    }

    val bytes: ByteArray = response.body()
    if (bytes.size.toLong() != action.size) {
        throw PluginException(
            PluginErrorCodes.INSTALL_VERIFY_FAILED,
            "Artifact for ${action.id}@${action.version} is ${bytes.size} bytes; the plan says ${action.size}",
        )
    }
    val digest = sha256Hex(bytes)
    if (digest != action.sha256) {
        throw PluginException(
            PluginErrorCodes.INSTALL_VERIFY_FAILED,
            "Artifact digest mismatch for ${action.id}@${action.version} (got $digest)",
        )
    }
    return bytes
}

/**
 * Orders staged plugins so dependencies apply before dependents.
 * A dep cycle will apply by input order and the post-apply load can report whatever fails.
 */
private fun orderStagedByDependencies(
    staged: List<Pair<RepoInstallAction, StagedPlugin>>,
): List<Pair<RepoInstallAction, StagedPlugin>> {
    val remaining = staged.associateBy { it.first.id }.toMutableMap()
    val ordered = mutableListOf<Pair<RepoInstallAction, StagedPlugin>>()

    while (remaining.isNotEmpty()) {
        val ready = remaining.values.filter { (action, plugin) ->
            plugin.manifest.dependencies.keys.none { it != action.id && it in remaining }
        }
        if (ready.isEmpty()) {
            ordered += remaining.values // Cycle, use input order.
            break
        }
        for (entry in ready) {
            ordered += entry
            remaining.remove(entry.first.id)
        }
    }
    return ordered
}
