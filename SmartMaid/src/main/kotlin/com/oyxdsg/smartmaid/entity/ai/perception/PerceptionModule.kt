package com.oyxdsg.smartmaid.entity.ai.perception

import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask
import com.oyxdsg.smartmaid.entity.ai.perception.sense.*
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import java.util.ArrayDeque
import java.util.function.Consumer

open class PerceptionModule(private val maid: SmartMaidEntity) {
    private val senses = mutableListOf<Sense>()
    private val events = ArrayDeque<JsonObject>()
    private var current: MaidPerception? = null
    private val eventSinks = mutableListOf<Consumer<JsonObject>>()
    private var lastHurtTimestamp = 0
    private var lastTaskId: String? = null
    private var lastTask: MaidAITask? = null
    private var lastInWater = false; private var lastInLava = false; private var lastOnFire = false
    private var lastHostileKey: String? = null
    init { senses.add(SelfSense()); senses.add(InventorySense()); senses.add(OwnerSense()); senses.add(EntitySense()); senses.add(BlockSense()); senses.add(EnvironmentSense()) }
    fun tick() {
        if (maid.level().isClientSide) return
        val previous = current; val data = MaidPerception(maid)
        senses.forEach { sense -> if (sense.intervalTicks() <= 1 || maid.tickCount % sense.intervalTicks() == 0) sense.collect(maid, data) else if (previous != null) data.copySection(previous, sense.sectionName()) }
        detectEvents(data); data.setEvents(ArrayList(events)); current = if (maid.tickCount >= READY_TICKS) data else null
        if (MaidDebug.verbose() && maid.tickCount % 40 == 0) MaidDebug.log("感知快照: " + data.toJson())
    }
    fun current(): MaidPerception? = current
    fun snapshotNow(): MaidPerception? {
        if (maid.level().isClientSide) return current
        val data = MaidPerception(maid); senses.forEach { try { it.collect(maid, data) } catch (_: Throwable) {} }; detectEvents(data); data.setEvents(ArrayList(events)); current = data; return data
    }
    fun pendingEvents(): List<JsonObject> = ArrayList(events)
    fun addEventSink(sink: Consumer<JsonObject>?) { if (sink != null && !eventSinks.contains(sink)) eventSinks.add(sink) }
    fun clearEventSinks() { eventSinks.clear() }
    fun reportEvent(type: String, payload: JsonObject?) { pushEvent(type, payload) }
    private fun detectEvents(data: MaidPerception) {
        val hurtTs = maid.lastHurtByMobTimestamp
        if (hurtTs > 0 && hurtTs != lastHurtTimestamp) { lastHurtTimestamp = hurtTs; val p = JsonObject(); p.addProperty("attacker", attackerLabel(maid.recentAttacker)); p.addProperty("health", maid.health); pushEvent("hurt", p) }
        val task = maid.maidTaskManager.currentTask(); val taskId = task?.taskId()
        if (taskId != lastTaskId) { if (lastTaskId != null && taskId == null) { val p = JsonObject(); p.addProperty("task", lastTaskId); lastTask?.resultJson()?.let { p.add("result", it) }; pushEvent("task_done", p) }; if (taskId != null) { val p = JsonObject(); p.addProperty("task", taskId); pushEvent("task_started", p) }; lastTaskId = taskId; lastTask = task }
        val inWater = maid.isInWater; val inLava = maid.isInLava; val onFire = maid.isOnFire
        if (inWater != lastInWater) { val p = JsonObject(); p.addProperty("danger", "water"); p.addProperty("enter", inWater); pushEvent("environment_danger", p); lastInWater = inWater }
        if (inLava != lastInLava) { val p = JsonObject(); p.addProperty("danger", "lava"); p.addProperty("enter", inLava); pushEvent("environment_danger", p); lastInLava = inLava }
        if (onFire != lastOnFire) { val p = JsonObject(); p.addProperty("danger", "fire"); p.addProperty("enter", onFire); pushEvent("environment_danger", p); lastOnFire = onFire }
        if (maid.tickCount % 20 == 0) { val hostile = PerceptionBlockUtil.findNearestHostile(maid, 16); val key = hostile?.let { it.type.toShortString() + "@" + it.blockPosition() }; if (key != lastHostileKey) { lastHostileKey = key; hostile?.let { val p = JsonObject(); p.addProperty("type", it.type.toShortString()); p.addProperty("pos", it.blockPosition().toString()); p.addProperty("dist", Math.round(maid.distanceTo(it) * 10.0) / 10.0); pushEvent("enemy_spotted", p) } } }
    }
    private fun attackerLabel(attacker: Entity?): String = when (attacker) { null -> "unknown"; is Player -> attacker.name.string; is Projectile -> attacker.owner?.let(::attackerLabel) ?: attacker.type.toShortString(); else -> attacker.type.toShortString() }
    private fun pushEvent(type: String, payload: JsonObject?) { val ev = JsonObject(); ev.addProperty("t", maid.level().levelData.gameTime); ev.addProperty("type", type); ev.add("data", payload ?: JsonObject()); events.addLast(ev); while (events.size > MAX_EVENTS) events.removeFirst(); eventSinks.forEach { it.accept(ev) } }
    companion object { private const val MAX_EVENTS = 16; private const val READY_TICKS = 40 }
}
