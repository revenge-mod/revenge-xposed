package io.github.revenge.bridge

import InternalApi
import io.github.revenge.bridge.impl.BridgeHost
import io.github.revenge.plugins.MethodArgs

@Suppress("UNUSED")
@OptIn(InternalApi::class)
/**
 * Bridge for plugins to communicate with the JavaScript side of the application.
 *
 * In order for a method to be exposed, you must register the method using the `modules` API.
 * Return values must be serializable by React Native, otherwise may result in unexpected behavior.
 *
 * ```js
 * import { registerJSMethod } from '@revenge-mod/modules/native'
 *
 * registerJSMethod('meaningOfLife', (arg1, arg2) => {
 *    console.log(arg1 + arg2)
 *    return 42
 * })
 * ```
 */
object JavaScriptBridge {
    fun callJSMethod(methodName: String, args: MethodArgs) =
        BridgeHost.callJSMethod!!.invoke(methodName, args)
}