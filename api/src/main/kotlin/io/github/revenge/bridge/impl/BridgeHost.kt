package io.github.revenge.bridge.impl

import InternalApi
import io.github.revenge.plugins.MethodArgs
import kotlinx.coroutines.CompletableDeferred

@Suppress("UNUSED")
@InternalApi
/**
 * Host for the bridge system to communicate with the application. For example, a Xposed module.
 */
object BridgeHost {
    /**
     * Callback for when a plugin calls a JS method.
     */
    var callJSMethod: ((methodName: String, args: MethodArgs) -> CompletableDeferred<Any?>)? = null
}