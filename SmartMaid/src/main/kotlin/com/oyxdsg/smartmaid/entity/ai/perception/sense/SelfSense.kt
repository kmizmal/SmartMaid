package com.oyxdsg.smartmaid.entity.ai.perception.sense

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionUtil
import com.oyxdsg.smartmaid.entity.ai.perception.Sense
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes

class SelfSense : Sense {
    override fun intervalTicks(): Int = 5
    override fun sectionName(): String = "self"

    override fun collect(maid: SmartMaidEntity, data: MaidPerception) {
        val self = data.self()
        PerceptionUtil.pos(self, maid.position())
        self.addProperty("health", maid.health)
        self.addProperty("max_health", maid.maxHealth)
        self.addProperty("air", maid.airSupply)
        self.addProperty("fire_ticks", maid.remainingFireTicks)
        self.addProperty("on_ground", maid.onGround())
        self.add("velocity", JsonObject().also { PerceptionUtil.pos(it, maid.deltaMovement) })
        self.addProperty("yaw", Math.round(maid.yRot * 10.0f) / 10.0f)
        self.addProperty("pitch", Math.round(maid.xRot * 10.0f) / 10.0f)
        self.addProperty("pose", maid.pose.name.lowercase())
        self.addProperty("armor_value", maid.armorValue)
        self.addProperty("absorption", maid.absorptionAmount)
        self.addProperty("sprinting", maid.isSprinting)
        self.addProperty("swimming", maid.isSwimming)
        self.addProperty("sleeping", maid.isSleeping)
        self.addProperty("blocking", maid.isBlocking)
        self.add("effects", JsonArray().also { PerceptionUtil.writeEffects(it, maid.activeEffects) })

        val feetPos = maid.blockPosition()
        self.add("safety", JsonObject().apply {
            addProperty("in_water", maid.isInWater)
            addProperty("in_lava", maid.isInLava)
            addProperty("on_fire", maid.isOnFire)
            addProperty("under_water", maid.isUnderWater)
            addProperty("feet", PerceptionUtil.classifyBlock(maid.level(), feetPos))
            addProperty("feet_id", PerceptionUtil.blockId(maid.level().getBlockState(feetPos)))
            addProperty("below", PerceptionUtil.classifyBlock(maid.level(), feetPos.below()))
            addProperty("below_id", PerceptionUtil.blockId(maid.level().getBlockState(feetPos.below())))
        })

        val equipment = JsonObject()
        writeItem(equipment, "mainhand", maid.mainHandItem)
        writeItem(equipment, "offhand", maid.offhandItem)
        writeItem(equipment, "head", maid.getItemBySlot(EquipmentSlot.HEAD))
        writeItem(equipment, "chest", maid.getItemBySlot(EquipmentSlot.CHEST))
        writeItem(equipment, "legs", maid.getItemBySlot(EquipmentSlot.LEGS))
        writeItem(equipment, "feet", maid.getItemBySlot(EquipmentSlot.FEET))
        self.add("equipment", equipment)

        self.add("attributes", JsonObject().apply {
            addProperty("movement_speed", round(maid.getAttributeValue(Attributes.MOVEMENT_SPEED)))
            addProperty("attack_damage", round(maid.getAttributeValue(Attributes.ATTACK_DAMAGE)))
            addProperty("follow_range", round(maid.getAttributeValue(Attributes.FOLLOW_RANGE)))
        })

        val current = maid.maidTaskManager.currentTask()
        self.add("status", JsonObject().apply {
            addProperty("ai_busy", maid.isAiBusy)
            addProperty("combat", maid.isCombatActive)
            addProperty("task_id", current?.taskId())
            addProperty("sitting", maid.isOrderedToSit)
            val target: LivingEntity? = maid.target
            if (target != null) add("target", JsonObject().apply {
                addProperty("type", target.type.toShortString())
                PerceptionUtil.blockPos(this, target.blockPosition())
            })
        })
        self.add("action", JsonObject().apply {
            addProperty("moving", maid.navigation.isInProgress)
            addProperty("jumping", !maid.onGround() && maid.deltaMovement.y > 0.05
                && !maid.isInWater && !maid.isInLava)
        })
    }

    private fun writeItem(equipment: JsonObject, key: String, stack: net.minecraft.world.item.ItemStack) {
        val o = JsonObject()
        equipment.add(key, o)
        PerceptionUtil.writeItem(o, stack, true)
    }

    private fun round(value: Double): Double = Math.round(value * 100.0) / 100.0
}
