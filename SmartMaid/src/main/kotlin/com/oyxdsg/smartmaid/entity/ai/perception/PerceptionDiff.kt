package com.oyxdsg.smartmaid.entity.ai.perception

import com.google.gson.JsonArray
import com.google.gson.JsonObject

class PerceptionDiff {
    private var prev: JsonObject? = null
    private var seq = 0L
    fun next(perception: MaidPerception): JsonObject {
        val snapshot = perception.root(); val out = JsonObject(); out.addProperty("seq", ++seq); out.addProperty("tick", perception.tick)
        val old = prev
        if (old == null) { out.addProperty("full", true); out.add("snapshot", snapshot); prev = snapshot.deepCopy(); return out }
        val changed = JsonObject(); val removed = JsonArray(); diff(old, snapshot, changed, removed, "")
        out.addProperty("full", false); out.add("changed", changed); out.add("removed", removed); prev = snapshot.deepCopy(); return out
    }
    fun reset() { prev = null; seq = 0 }
    fun seq(): Long = seq

    companion object {
        private val SKIP_KEYS = setOf("tick", "events")
        private fun diff(prev: JsonObject, cur: JsonObject, changed: JsonObject, removed: JsonArray, prefix: String) {
            cur.entrySet().forEach { entry -> val key = entry.key; if (prefix.isEmpty() && key in SKIP_KEYS) return@forEach; val curVal = entry.value; val prevVal = prev.get(key); when { prevVal == null -> changed.add(key, curVal.deepCopy()); prevVal.isJsonObject && curVal.isJsonObject -> { val subChanged = JsonObject(); val subRemoved = JsonArray(); diff(prevVal.asJsonObject, curVal.asJsonObject, subChanged, subRemoved, "$prefix$key."); if (!subChanged.isEmpty) changed.add(key, subChanged); subRemoved.forEach(removed::add) }; prevVal != curVal -> changed.add(key, curVal.deepCopy()) } }
            prev.entrySet().forEach { entry -> val key = entry.key; if (prefix.isEmpty() && key in SKIP_KEYS) return@forEach; if (!cur.has(key)) removed.add(prefix + key) }
        }
    }
}
