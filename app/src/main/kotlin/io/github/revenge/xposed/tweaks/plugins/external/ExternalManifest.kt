package io.github.revenge.xposed.tweaks.plugins.external

import io.github.revenge.plugins.PluginDependency
import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange
import io.github.revenge.xposed.tweaks.plugins.internal.RESERVED_DEPENDENCY_IDS
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val MANIFEST_FILE = "manifest.json"

internal const val MANIFEST_FORMAT = 1

@Serializable
internal data class ExternalManifest(
    /** Manifest format version. Only [MANIFEST_FORMAT] is accepted. */
    val format: Int,
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    /* See [validatedPluginIcon]. */
    val icon: String? = null,
    /** The plugin's version. */
    val version: String,
    /** Dependencies keyed by plugin ID: `{ "<id>": { "version"?: "<range>", "optional"?: true } }`. */
    val dependencies: Map<String, ExternalDependency> = emptyMap(),
    val dist: ExternalDist? = null,
) {
    fun toPluginManifest() = PluginManifest(
        id = id,
        name = name,
        description = description,
        author = author,
        icon = icon?.let(::validatedPluginIcon),
        dependencies = dependencies.entries.associate { (depId, dep) ->
            requireValidPluginId(depId)
            depId to PluginDependency(
                version = dep.version?.let(VersionRange::parse) ?: VersionRange.ANY,
                optional = dep.optional,
            )
        },
        version = Version.parse(version),
    )
}

@Serializable
internal data class ExternalDependency(
    /** Version range. `null` (empty object in JSON) means `"*"`. */
    val version: String? = null,
    /** Optional dependencies won't block the dependent. */
    val optional: Boolean = false,
)

@Serializable
internal data class ExternalDist(
    val script: String? = null,
    val android: ExternalAndroidDist? = null,
)

@Serializable
internal data class ExternalAndroidDist(
    val path: String,
    @SerialName("class") val className: String,
)

/** Longest accepted `data:` icon value. */
private const val MAX_ICON_DATA_URL_LENGTH = 128 * 1024

private val URI_SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/**
 * Icons are either a packaged asset name or a size-capped `data:` URL.
 * Anything else is stripped. Icons are never fetched over the network.
 */
internal fun validatedPluginIcon(icon: String): String? = when {
    icon.startsWith("data:") -> icon.takeIf { it.length <= MAX_ICON_DATA_URL_LENGTH }
    URI_SCHEME_REGEX.containsMatchIn(icon) -> null
    else -> icon
}

internal fun ExternalManifest.validate() {
    require(format == MANIFEST_FORMAT) {
        "Plugin '$id' has unsupported manifest format $format (supported: $MANIFEST_FORMAT)"
    }
    requireValidPluginId(id)
    Version.parse(version)

    for (reservedId in RESERVED_DEPENDENCY_IDS) {
        require(reservedId in dependencies) {
            "Plugin '$id' does not declare the mandatory '$reservedId' dependency"
        }
    }
    for ((depId, dep) in dependencies) {
        requireValidPluginId(depId)
        dep.version?.let(VersionRange::parse)
        require(!(dep.optional && depId in RESERVED_DEPENDENCY_IDS)) {
            "Plugin '$id': the '$depId' dependency cannot be optional"
        }
    }
}

private val PLUGIN_ID_REGEX = Regex("[a-zA-Z0-9.-]+")

/** Matches `[a-zA-Z0-9.-]`, with no consecutive dots or leading dot. */
internal fun requireValidPluginId(id: String) {
    require(PLUGIN_ID_REGEX.matches(id) && ".." !in id && !id.startsWith(".")) {
        "Invalid plugin ID: $id"
    }
}
