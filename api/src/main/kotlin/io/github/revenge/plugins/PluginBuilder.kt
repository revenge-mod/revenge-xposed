package io.github.revenge.plugins

class PluginBuilder {
    private var startBlock: PluginScope.() -> Unit = {}
    private var stopBlock: PluginScope.() -> Unit = {}

    fun start(block: PluginScope.() -> Unit) {
        startBlock = block
    }

    fun stop(block: PluginScope.() -> Unit) {
        stopBlock = block
    }

    fun build(manifest: PluginManifest): Plugin {
        return object : Plugin(manifest) {
            override fun start(ctx: PluginScope) = ctx.startBlock()
            override fun stop(ctx: PluginScope) = ctx.stopBlock()
        }
    }
}

/**
 * Declaratively create a plugin.
 *
 * ```kotlin
 * @file:JvmName("MyPlugin") // so dist.android.class can be `com.example.MyPlugin`
 * package com.example
 *
 * val myPlugin = plugin {
 *   start {
 *      log.i("Hello, world!")
 *   }
 *
 *   stop {
 *      log.i("Goodbye, world!")
 *   }
 * }
 * ```
 */
fun plugin(block: PluginBuilder.() -> Unit): PluginBuilder {
    val builder = PluginBuilder()
    builder.block()
    return builder
}