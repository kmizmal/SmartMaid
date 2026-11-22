package com.oyxdsg.smartmaid.entity.ai.bridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlin.jvm.JvmRecord

/** AI 指令回执模型。 */
@JvmRecord
data class MaidCommandResult(
    val id: String,
    val ok: Boolean,
    val state: String,
    val step: String,
    val result: JsonObject
) {
    companion object {
        private val GSON = Gson()

        @JvmStatic
        fun ok(id: String, state: String, step: String, result: JsonObject?): MaidCommandResult =
            MaidCommandResult(id, true, state, step, result ?: JsonObject())

        @JvmStatic
        fun fail(id: String, reason: String): MaidCommandResult =
            MaidCommandResult(id, false, "failed", "rejected", JsonObject().apply {
                addProperty("error", reason)
            })
    }

    fun toJson(): String = GSON.toJson(JsonObject().apply {
        addProperty("id", id)
        addProperty("ok", ok)
        addProperty("state", state)
        addProperty("step", step)
        add("result", result)
    })
}
