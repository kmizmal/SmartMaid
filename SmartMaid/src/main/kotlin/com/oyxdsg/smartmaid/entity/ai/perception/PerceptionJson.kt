package com.oyxdsg.smartmaid.entity.ai.perception

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object PerceptionJson {
    @JvmStatic fun snapshot(perception: MaidPerception): String = perception.toJson()
    @JvmStatic fun events(events: List<JsonObject>): String {
        val arr = JsonArray(); events.forEach(arr::add); return arr.toString()
    }
}
