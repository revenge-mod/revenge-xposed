@file:Suppress("DEPRECATION")

package io.github.revenge.xposed.tweaks.legacy

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.revenge.Logger
import io.github.revenge.xposed.*
import io.github.revenge.xposed.tweaks.base.registerScriptInjector
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

@Deprecated(
    "Payloads will be replaced by synchronous Revenge bridge methods.",
    level = DeprecationLevel.WARNING,
)
object RevengePayloadBuilder {
    private val contributors: MutableList<JsonObjectBuilder.() -> Unit> = mutableListOf()

    fun contribute(block: JsonObjectBuilder.() -> Unit) {
        contributors += block
    }

    internal fun build(): String = RevengeJson.encodeToString(
        buildJsonObject {
            put("loaderName", RevengeConstants.LOADER_NAME)
            put("loaderVersion", RevengeConstants.LOADER_VERSION)
            for (c in contributors) c()
        },
    )
}

internal const val FIELD_NAME = "__PYON_LOADER__"

/**
 * Sets the `__PYON_LOADER__` JS global before the bundle runs.
 */
@Deprecated(
    "Payloads will be replaced by synchronous Revenge bridge methods.",
    level = DeprecationLevel.WARNING,
)
val revengePayloadGlobal by tweak {
    val log: Logger = this.log
    val dataDir = appInfo.dataDir

    registerScriptInjector { scope ->
        val json = RevengePayloadBuilder.build()

        val preloadsDir = File("$dataDir/${RevengeConstants.FILES_DIR}", RevengeConstants.PRELOADS_DIR)
        if (!preloadsDir.isDirectory) {
            preloadsDir.delete()
            preloadsDir.mkdirs()
        }

        File(preloadsDir, "rv_globals_$FIELD_NAME.js").apply {
            writeText("this[${JsonPrimitive(FIELD_NAME)}]=$json")
            try {
                scope.runFile(absolutePath)
            } catch (e: Throwable) {
                log.e("rv_globals_$FIELD_NAME.js preload injection failed", e)
            } finally {
                delete()
            }
        }
    }
}
