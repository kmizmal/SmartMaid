package com.oyxdsg.smartmaid.entity.ai.script

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.ArrayList
import java.util.LinkedHashMap
import kotlin.jvm.JvmField

/** 脚本执行上下文：变量表、步骤日志、终止信号与执行步数。 */
class ScriptContext {
    @JvmField
    val vars: MutableMap<String, JsonElement> = LinkedHashMap()

    @JvmField
    val stepLog: MutableList<JsonObject> = ArrayList()

    @JvmField
    var lastResult: JsonElement? = null

    @JvmField
    var terminateReason: String? = null

    @JvmField
    var terminateResult: JsonObject? = null

    @JvmField
    var terminated: Boolean = false

    @JvmField
    var executedSteps: Int = 0

    @JvmField
    var scriptName: String = "script"

    fun logStep(cmd: String, ok: Boolean, result: JsonElement?) {
        val rec = JsonObject()
        rec.addProperty("cmd", cmd)
        rec.addProperty("ok", ok)
        if (result != null) rec.add("result", result)
        stepLog.add(rec)
        lastResult = result
    }
}
