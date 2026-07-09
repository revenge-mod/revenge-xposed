package io.github.revenge.bridge

/**
 * - JS -> native: `{ revenge: { method, args: [...] } }` passed to either `RNSVGRenderableManager.getBBox` (sync, fast)
 *   or `FileReaderModule.readAsDataURL` (alternative path).
 *
 * - Native -> JS: JS registers a callable module named `RevengeBridge`; native code invokes
 *   `ReactInstance.callFunctionOnModule("RevengeBridge", method, NativeArray)`.
 *   JS replies via `revenge.__callableReturn`.
 */
interface RevengeBridge {
    /**
     * Register a native method callable from JS.
     *
     * If [name] is already registered, the new handler replaces the old one and a warning is logged.
     * 
     * Arguments and return values are converted by React Native:
     * https://github.com/facebook/react-native/blob/main/packages/react-native/ReactAndroid/src/main/java/com/facebook/react/bridge/Arguments.kt
     *
     * Additionally, `Unit` is converted to `null`.
     */
    fun registerMethod(name: String, handler: (args: List<Any?>) -> Any?)

    /**
     * Invoke a JS method on the `RevengeBridge` callable module and await JS's `revenge.__callableReturn` reply.
     *
     * Throws if JS responds with `{ error: ... }`. May suspend indefinitely if JS never replies.
     */
    suspend fun callJSMethod(name: String, args: List<Any?> = emptyList()): Any?
}
