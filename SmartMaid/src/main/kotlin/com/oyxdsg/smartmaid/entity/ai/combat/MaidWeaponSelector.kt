package com.oyxdsg.smartmaid.entity.ai.combat

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import net.minecraft.core.Holder
import net.minecraft.util.Mth
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeInstance
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack

object MaidWeaponSelector {
    private const val MIN_ATTACK_COOLDOWN = 14
    private const val MAX_ATTACK_COOLDOWN = 26
    @JvmStatic fun meleeScore(maid: SmartMaidEntity, stack: ItemStack): Double { if (stack.isEmpty) return 0.0; val bonus = modifiers(stack); if (bonus[0] <= 0.0 && bonus[1] <= 0.0) return 0.0; return kotlin.math.max(0.0, baseValue(maid, Attributes.ATTACK_DAMAGE) + bonus[0]) * attackSpeed(maid, stack) }
    @JvmStatic fun meleeCooldownTicks(maid: SmartMaidEntity): Int { val ticks = kotlin.math.round(20.0 / attackSpeed(maid, maid.maidInventory.getItem(0))).toInt(); return Mth.clamp(ticks, MIN_ATTACK_COOLDOWN, MAX_ATTACK_COOLDOWN) }
    @JvmStatic fun equipBestMelee(maid: SmartMaidEntity) { val inv = maid.maidInventory; var bestSlot = -1; var bestScore = 0.0; for (i in 0 until inv.containerSize) { val score = meleeScore(maid, inv.getItem(i)); if (score > bestScore) { bestScore = score; bestSlot = i } }; if (bestSlot < 0 || bestSlot == 0) return; val hand = inv.getItem(0); inv.setItem(0, inv.getItem(bestSlot)); inv.setItem(bestSlot, hand); inv.setChanged(); maid.syncInventoryArmor(); MaidDebug.log("Combat equipMelee: 槽 $bestSlot -> 主手 ${MaidDebug.itemName(inv.getItem(0))}") }
    private fun attackSpeed(maid: SmartMaidEntity, stack: ItemStack): Double = kotlin.math.max(0.1, baseValue(maid, Attributes.ATTACK_SPEED) + if (stack.isEmpty) 0.0 else modifiers(stack)[1])
    private fun modifiers(stack: ItemStack): DoubleArray { val bonus = doubleArrayOf(0.0, 0.0); stack.forEachModifier(EquipmentSlot.MAINHAND) { attribute, modifier -> if (attribute.`is`(Attributes.ATTACK_DAMAGE)) bonus[0] += modifier.amount() else if (attribute.`is`(Attributes.ATTACK_SPEED)) bonus[1] += modifier.amount() }; return bonus }
    private fun baseValue(maid: SmartMaidEntity, attribute: Holder<Attribute>): Double { val instance: AttributeInstance? = maid.getAttribute(attribute); return instance?.baseValue ?: 0.0 }
}
