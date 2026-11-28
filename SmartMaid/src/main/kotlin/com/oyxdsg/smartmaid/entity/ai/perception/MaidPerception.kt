package com.oyxdsg.smartmaid.entity.ai.perception

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import kotlin.jvm.JvmField

/** 感知快照：AI 的世界与女仆状态数据模型。 */
class MaidPerception(maid: SmartMaidEntity) {
    companion object {
        private val GSON = Gson()
    }

    @JvmField
    val tick: Long = maid.level().levelData.gameTime
    private val root = JsonObject()

    init {
        root.addProperty("tick", tick)
        root.addProperty("dim", maid.level().dimension().identifier().toString())
        root.add("self", JsonObject())
        root.add("inventory", JsonObject())
        root.add("owner", JsonObject())
        root.add("nearby", JsonObject())
        root.add("blocks", JsonObject())
        root.add("env", JsonObject())
        root.add("events", JsonArray())
    }

    fun self(): JsonObject = root.getAsJsonObject("self")
    fun inventory(): JsonObject = root.getAsJsonObject("inventory")
    fun owner(): JsonObject = root.getAsJsonObject("owner")
    fun nearby(): JsonObject = root.getAsJsonObject("nearby")
    fun blocks(): JsonObject = root.getAsJsonObject("blocks")
    fun env(): JsonObject = root.getAsJsonObject("env")
    fun events(): JsonArray = root.getAsJsonArray("events")

    fun setEvents(eventList: List<JsonObject>) {
        val arr = JsonArray()
        for (event in eventList) arr.add(event)
        root.add("events", arr)
    }

    fun copySection(other: MaidPerception, sectionName: String) {
        val src = other.root.getAsJsonObject(sectionName)
        if (src != null) root.add(sectionName, src)
    }

    fun root(): JsonObject = root

    fun toJson(): String = GSON.toJson(root)
}
