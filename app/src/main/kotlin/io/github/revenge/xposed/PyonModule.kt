package io.github.revenge.xposed

import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.json.JsonObjectBuilder

abstract class Module {
    open fun buildJson(builder: JsonObjectBuilder) {}
    open fun onInit(packageParam: XC_LoadPackage.LoadPackageParam) {}
}