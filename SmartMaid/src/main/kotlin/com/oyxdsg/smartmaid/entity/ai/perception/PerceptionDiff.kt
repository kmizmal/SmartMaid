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
    fun hasBaseline(): Boolean = prev != null
    companion object {
        private val SKIP_KEYS = setOf("tick", "events")
        @JvmStatic fun merge(base: JsonObject, message: JsonObject) {
            if (message.has("full") && message.get("full").asBoolean) { val snapshot = message.getAsJsonObject("snapshot"); base.keySet().toList().forEach(base::remove); snapshot.entrySet().forEach { e -> base.add(e.key, e.value.deepCopy()) }; return }
            if (message.has("changed") && message.get("changed").isJsonObject) mergeInto(base, message.getAsJsonObject("changed"))
            if (message.has("removed")) message.getAsJsonArray("removed").forEach { removePath(base, it.asString) }
        }
        private fun diff(prev: JsonObject, cur: JsonObject, changed: JsonObject, removed: JsonArray, prefix: String) {
            cur.entrySet().forEach { entry -> val key = entry.key; if (prefix.isEmpty() && key in SKIP_KEYS) return@forEach; val curVal = entry.value; val prevVal = prev.get(key); when { prevVal == null -> changed.add(key, curVal.deepCopy()); prevVal.isJsonObject && curVal.isJsonObject -> { val subChanged = JsonObject(); val subRemoved = JsonArray(); diff(prevVal.asJsonObject, curVal.asJsonObject, subChanged, subRemoved, "$prefix$key."); if (!subChanged.isEmpty) changed.add(key, subChanged); subRemoved.forEach(removed::add) }; prevVal != curVal -> changed.add(key, curVal.deepCopy()) } }
            prev.entrySet().forEach { entry -> val key = entry.key; if (prefix.isEmpty() && key in SKIP_KEYS) return@forEach; if (!cur.has(key)) removed.add(prefix + key) }
        }
        private fun mergeInto(base: JsonObject, changed: JsonObject) { changed.entrySet().forEach { entry -> val value = entry.value; val existing = base.get(entry.key); if (value.isJsonObject && existing != null && existing.isJsonObject) mergeInto(existing.asJsonObject, value.asJsonObject) else base.add(entry.key, value.deepCopy()) } }
        private fun removePath(base: JsonObject, path: String) { val dot = path.lastIndexOf('.'); var parent = base; if (dot > 0) path.substring(0, dot).split('.').forEach { part -> val next = parent.get(part) ?: return; if (!next.isJsonObject) return; parent = next.asJsonObject }; parent.remove(if (dot > 0) path.substring(dot + 1) else path) }
    }
}
