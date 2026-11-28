package com.oyxdsg.smartmaid.entity.ai.perception.sense

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionUtil
import com.oyxdsg.smartmaid.entity.ai.perception.Sense
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player

open class EntitySense : Sense {
    override fun intervalTicks() = 10
    override fun sectionName() = "nearby"
    override fun collect(maid: SmartMaidEntity, data: MaidPerception) {
        val nearby = data.nearby(); val living = MaidActions.findEntities(maid, LivingEntity::class.java, 16.0) { it.isAlive && it !is SmartMaidEntity }.toMutableList(); living.sortBy { maid.distanceToSqr(it) }
        val hostiles = JsonArray(); val animals = JsonArray(); val players = JsonArray(); val owner = maid.owner as? Player; var hc = 0; var ac = 0; var pc = 0
        living.forEach { e -> when { e is Monster && hc < 8 -> { hostiles.add(hostileEntry(maid, e)); hc++ }; e is AgeableMob && ac < 8 -> { animals.add(animalEntry(maid, e)); ac++ }; e is Player && e !== owner && pc < 8 -> { val o = JsonObject(); o.addProperty("name", e.name.string); PerceptionUtil.pos(o, e.position()); o.addProperty("dist", round(maid.distanceTo(e).toDouble())); players.add(o); pc++ } } }
        nearby.add("hostiles", hostiles); nearby.add("animals", animals); nearby.add("players", players)
        val items = JsonArray(); val itemList = MaidActions.findEntities(maid, ItemEntity::class.java, 16.0, Entity::isAlive).toMutableList(); itemList.sortBy { maid.distanceToSqr(it) }; itemList.take(8).forEach { e -> val o = JsonObject(); PerceptionUtil.writeItem(o, e.item, false); PerceptionUtil.pos(o, e.position()); o.addProperty("dist", round(maid.distanceTo(e).toDouble())); items.add(o) }; nearby.add("items", items)
    }
    private fun hostileEntry(maid: SmartMaidEntity, e: LivingEntity): JsonObject { val o = JsonObject(); o.addProperty("type", e.type.toShortString()); PerceptionUtil.pos(o, e.position()); o.addProperty("dist", round(maid.distanceTo(e).toDouble())); o.addProperty("health", e.health); o.addProperty("max_health", e.maxHealth); o.addProperty("targeting_me", e is Mob && e.target === maid); o.addProperty("has_los", maid.hasLineOfSight(e)); return o }
    private fun animalEntry(maid: SmartMaidEntity, e: LivingEntity): JsonObject { val o = JsonObject(); o.addProperty("type", e.type.toShortString()); PerceptionUtil.pos(o, e.position()); o.addProperty("dist", round(maid.distanceTo(e).toDouble())); o.addProperty("health", e.health); o.addProperty("baby", e.isBaby); return o }
    private fun round(v: Double) = Math.round(v * 10.0) / 10.0
}
