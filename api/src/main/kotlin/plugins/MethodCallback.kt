package io.github.revenge.plugins

/**
 * See for possible return types:
 * https://github.com/facebook/react-native/blob/c23e84ae9/packages/react-native/ReactAndroid/src/main/java/com/facebook/react/bridge/Arguments.kt#L19
 *
 * You may return a [Unit] and the resulting value will be `null`.
 */
typealias MethodCallback = (args: MethodArgs) -> Any?

typealias MethodArgs = ArrayList<Any?>