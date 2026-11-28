package com.oyxdsg.smartmaid.entity.ai.perception.sense

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionUtil
import com.oyxdsg.smartmaid.entity.ai.perception.Sense
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import kotlin.math.roundToLong

class OwnerSense : Sense {
    override fun intervalTicks(): Int = 10
    override fun sectionName(): String = "owner"

    override fun collect(maid: SmartMaidEntity, data: MaidPerception) {
        val owner = maid.owner as? Player ?: return
        val ownerObj = data.owner()
        ownerObj.addProperty("name", owner.name.string)
        ownerObj.addProperty("uuid", owner.uuid.toString())
        PerceptionUtil.blockPos(ownerObj, owner.blockPosition())
        PerceptionUtil.pos(ownerObj, owner.position())
        ownerObj.addProperty("dist", (maid.distanceTo(owner) * 10.0).roundToLong() / 10.0)
        ownerObj.addProperty("health", owner.health)
        ownerObj.addProperty("max_health", owner.maxHealth)
        ownerObj.addProperty("armor_value", owner.armorValue)
        ownerObj.addProperty("food", owner.foodData.foodLevel)
        ownerObj.addProperty("saturation", owner.foodData.saturationLevel)
        ownerObj.addProperty("xp_level", owner.experienceLevel)
        ownerObj.addProperty("total_xp", owner.totalExperience)
        ownerObj.add("effects", JsonArray().also { PerceptionUtil.writeEffects(it, owner.activeEffects) })

        val equipment = JsonObject()
        writeEquip(equipment, "mainhand", owner)
        writeEquip(equipment, "offhand", owner)
        writeEquip(equipment, "head", owner)
        writeEquip(equipment, "chest", owner)
        writeEquip(equipment, "legs", owner)
        writeEquip(equipment, "feet", owner)
        ownerObj.add("equipment", equipment)
        ownerObj.addProperty("gamemode", owner.gameMode()?.name ?: "unknown")
        ownerObj.addProperty("on_ground", owner.onGround())
        ownerObj.addProperty("swimming", owner.isSwimming)
        ownerObj.addProperty("sleeping", owner.isSleeping)
        ownerObj.addProperty("blocking", owner.isBlocking)
        ownerObj.addProperty("hurt_recently", owner.lastHurtByMobTimestamp > 0)
    }

    private fun writeEquip(equipment: JsonObject, key: String, player: Player) {
        val o = JsonObject()
        equipment.add(key, o)
        val stack = when (key) {
            "mainhand" -> player.mainHandItem
            "offhand" -> player.offhandItem
            "head" -> player.getItemBySlot(EquipmentSlot.HEAD)
            "chest" -> player.getItemBySlot(EquipmentSlot.CHEST)
            "legs" -> player.getItemBySlot(EquipmentSlot.LEGS)
            else -> player.getItemBySlot(EquipmentSlot.FEET)
        }
        PerceptionUtil.writeItem(o, stack, true)
    }
}
