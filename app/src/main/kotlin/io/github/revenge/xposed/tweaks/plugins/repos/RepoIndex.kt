package io.github.revenge.xposed.tweaks.plugins.repos

import io.github.revenge.Logger
import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange
import io.github.revenge.xposed.RevengeJson
import io.github.revenge.xposed.tweaks.plugins.ExternalDependency
import io.github.revenge.xposed.tweaks.plugins.requireValidPluginId
import io.github.revenge.xposed.tweaks.plugins.validatedPluginIcon
import kotlinx.serialization.Serializable

/** The supported repository `index.json` format version. */
internal const val REPO_INDEX_FORMAT = 1

/**
 * A plugin repository index: one static `index.json` served over HTTPS.
 *
 * The repository's URL is its identity. Everything the client needs to browse, resolve
 * and install plugins from the repository is in this single document; artifact integrity
 * is anchored through per-version [RepoVersion.sha256] digests.
 */
@Serializable
internal data class RepoIndex(
    /** Only [REPO_INDEX_FORMAT] is accepted. */
    val format: Int,
    val name: String,
    val description: String = "",
    /** A Discord asset name or a `data:` URL. */
    val icon: String? = null,
    /** Plugins keyed by plugin ID. */
    val plugins: Map<String, RepoPlugin> = emptyMap(),
)

/** A plugin as listed in a repository index. */
@Serializable
internal data class RepoPlugin(
    val name: String,
    val description: String = "",
    val author: String = "",
    /** A Discord asset name or a `data:` URL. */
    val icon: String? = null,
    /**
     * Channel pointers (e.g. `latest`, `testing`), each naming a key of [versions].
     *
     * `latest` is the default install/update channel.
     */
    val channels: Map<String, String> = emptyMap(),
    /** Published versions keyed by their exact version string. */
    val versions: Map<String, RepoVersion> = emptyMap(),
)

/** One published version of a plugin in a repository index. */
@Serializable
internal data class RepoVersion(
    /** Absolute URL of the plugin ZIP artifact. */
    val url: String,
    /** Lowercase hex SHA-256 digest of the artifact. */
    val sha256: String,
    /** Artifact size in bytes. */
    val size: Long,
    /**
     * The version's dependencies as they appear in its manifest.
     *
     * Used for client-side resolution before download.
     * Any mismatch between the index and the artifact's manifest aborts the install.
     */
    val dependencies: Map<String, ExternalDependency> = emptyMap(),
)

/** The default channel used for installs and update checks. */
internal const val REPO_CHANNEL_LATEST = "latest"

private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")

private fun isAbsoluteHttpUrl(url: String) =
    url.startsWith("https://") || url.startsWith("http://")

/**
 * Parse and sanitize a repository index document.
 *
 * Fails (throws) only on unparseable JSON or an unsupported [RepoIndex.format], with these extra rules:
 * - Icons that aren't asset names or data URLs are stripped (see [validatedPluginIcon]).
 * - Structurally invalid versions (bad version grammar, non-absolute [RepoVersion.url], malformed [RepoVersion.sha256],
 *   non-positive size, invalid dependency ids/ranges) are dropped with a warning.
 * - Channel pointers naming a missing/dropped version are dropped with a warning.
 * - Plugins with an invalid ID or no remaining versions are dropped with a warning.
 */
internal fun parseRepoIndex(json: String, repoUrl: String, log: Logger): RepoIndex {
    val raw = RevengeJson.decodeFromString<RepoIndex>(json)
    require(raw.format == REPO_INDEX_FORMAT) {
        "Repository '$repoUrl' has unsupported index format ${raw.format} (supported: $REPO_INDEX_FORMAT)"
    }

    fun warn(message: String) = log.w("Repository '$repoUrl': $message")

    val plugins = buildMap {
        for ((id, plugin) in raw.plugins) {
            try {
                requireValidPluginId(id)
            } catch (e: IllegalArgumentException) {
                warn("dropped plugin entry '$id': ${e.message}")
                continue
            }

            val versions = buildMap {
                for ((versionKey, version) in plugin.versions) {
                    val problem = validateRepoVersion(versionKey, version)
                    if (problem != null) {
                        warn("dropped version '$id@$versionKey': $problem")
                        continue
                    }
                    put(versionKey, version)
                }
            }
            if (versions.isEmpty()) {
                warn("dropped plugin entry '$id': no valid versions")
                continue
            }

            val channels = buildMap {
                for ((channel, target) in plugin.channels) {
                    if (target !in versions) {
                        warn("dropped channel '$id/$channel': it points to missing version '$target'")
                        continue
                    }
                    put(channel, target)
                }
            }

            put(id, plugin.copy(icon = plugin.icon?.let(::validatedPluginIcon), channels = channels, versions = versions))
        }
    }

    return raw.copy(icon = raw.icon?.let(::validatedPluginIcon), plugins = plugins)
}

/** Returns a human-readable problem description, or `null` if the version entry is valid. */
private fun validateRepoVersion(versionKey: String, version: RepoVersion): String? {
    try {
        Version.parse(versionKey)
    } catch (e: IllegalArgumentException) {
        return "invalid version string: ${e.message}"
    }
    if (!isAbsoluteHttpUrl(version.url)) return "url must be absolute: '${version.url}'"
    if (!SHA256_HEX_REGEX.matches(version.sha256)) return "malformed sha256 digest"
    if (version.size <= 0) return "non-positive size"
    for ((depId, dep) in version.dependencies) {
        try {
            requireValidPluginId(depId)
            dep.version?.let(VersionRange::parse)
        } catch (e: IllegalArgumentException) {
            return "invalid dependency '$depId': ${e.message}"
        }
    }
    return null
}
