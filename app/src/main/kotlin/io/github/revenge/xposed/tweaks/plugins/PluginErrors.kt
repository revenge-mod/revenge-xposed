package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.xposed.api.HostScope
import io.github.revenge.xposed.api.registerNativeAsyncMethod
import io.github.revenge.xposed.api.registerNativeMethod
import io.github.revenge.xposed.tweaks.bridge.RevengeBridgeRegistry.toNativeObject

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

    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"

    /** Disallowed actions on essential plugins, internal plugins, reserved repository URLs. */
    const val NOT_ALLOWED = "NOT_ALLOWED"

    const val NOT_FOUND = "NOT_FOUND"
    const val RESOLVE_FAILED = "RESOLVE_FAILED"

    /** Can't enable, required dependencies are missing or out of range. */
    const val DEPENDENCIES_UNSATISFIED = "DEPENDENCIES_UNSATISFIED"

    const val STORAGE_FAILED = "STORAGE_FAILED"

    /** Anything that isn't a [PluginSystemError]. */
    const val UNKNOWN = "UNKNOWN"
}

internal open class PluginSystemError(
    val code: String,
    message: String,
    cause: Throwable? = null,
    val details: Map<String, Any?>? = null,
) : Exception(message, cause)

internal class PluginError(
    val code: String,
    val message: String,
    val stack: String? = null,
    val details: Map<String, Any?>? = null,
) {
    fun toJSPayload(): Map<String, Any?> = buildMap {
        put("code", code)
        put("message", message)
        put("stack", stack)
        if (details != null) put("details", details)
    }
}

internal fun Throwable.toPluginError(fallbackCode: String): PluginError = PluginError(
    code = (this as? PluginSystemError)?.code ?: fallbackCode,
    message = message ?: toString(),
    stack = stackTraceToString(),
    details = (this as? PluginSystemError)?.details,
)

// We ensure JS always receives a "successful bridge call" with `{ result | error }`, so enough details can be given.

internal fun Any?.toJSPayload(): Map<String, Any?> = mapOf("result" to this?.toNativeObject())

internal fun Throwable.toJSPayload(): Map<String, Any?> {
    // Arg delegates and require() throw IllegalArgumentException, this is likely JS' fault.
    val fallback = if (this is IllegalArgumentException) PluginErrorCodes.INVALID_ARGUMENT else PluginErrorCodes.UNKNOWN
    return mapOf("error" to toPluginError(fallback).toJSPayload())
}

internal fun HostScope.pluginSystemMethod(name: String, handler: (args: List<Any?>) -> Any?) =
    registerNativeMethod(name) { args ->
        try {
            handler(args).toJSPayload()
        } catch (e: Throwable) {
            e.toJSPayload()
        }
    }

internal fun HostScope.pluginSystemAsyncMethod(name: String, handler: suspend (args: List<Any?>) -> Any?) =
    registerNativeAsyncMethod(name) { args ->
        try {
            handler(args).toJSPayload()
        } catch (e: Throwable) {
            e.toJSPayload()
        }
    }
