package io.github.revenge.xposed.tweaks.plugins

internal object PluginErrorCodes {
    const val MANIFEST_INVALID = "MANIFEST_INVALID"
    const val DEPENDENCY_MISSING = "DEPENDENCY_MISSING"
    const val DEPENDENCY_UNSATISFIED = "DEPENDENCY_UNSATISFIED"
    const val DEPENDENCY_FAILED = "DEPENDENCY_FAILED"
    const val DEPENDENCY_CYCLE = "DEPENDENCY_CYCLE"
    const val LOAD_FAILED = "LOAD_FAILED"

    /* The plugin threw. */
    const val PLUGIN_ERROR = "PLUGIN_ERROR"
    
    const val INSTALL_INVALID_ZIP = "INSTALL_INVALID_ZIP"
    const val INSTALL_VERIFY_FAILED = "INSTALL_VERIFY_FAILED"
    const val INSTALL_MISMATCH = "INSTALL_MISMATCH"
    const val INSTALL_FAILED = "INSTALL_FAILED"
}

internal class PluginException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal class PluginErrorInfo(
    val code: String,
    val message: String,
    val stack: String? = null,
) {
    fun toJSPayload(): Map<String, Any?> = mapOf(
        "code" to code,
        "message" to message,
        "stack" to stack,
    )
}

internal fun Throwable.toPluginErrorInfo(fallbackCode: String): PluginErrorInfo = PluginErrorInfo(
    code = (this as? PluginException)?.code ?: fallbackCode,
    message = message ?: toString(),
    stack = stackTraceToString(),
)
