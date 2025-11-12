package io.github.revenge.xposed.plugins

import io.github.revenge.plugins.plugin
import io.github.revenge.xposed.Utils.Log

val myPlugin = plugin {
    init { _, _ ->
        Log.i("plugin.test called")
    }

    "callback" { args ->
        "js"(args[1])

        Unit
    }
}