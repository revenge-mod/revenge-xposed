package io.github.revenge.plugins

/**
 * You may return a [Unit] and it will be converted to `null`.
 *
 * See [NativeObject] for more information.
 */
typealias MethodCallback = (args: MethodArgs) -> NativeObject

typealias MethodArgs = ArrayList<NativeObject>

/**
 * Represents a React Native JNI serializable object. These objects can be passed to JavaScript as values.
 *
 * See: https://github.com/facebook/react-native/blob/7dcedf1/packages/react-native/ReactAndroid/src/main/java/com/facebook/react/bridge/Arguments.kt#L19
 */
typealias NativeObject = Any?