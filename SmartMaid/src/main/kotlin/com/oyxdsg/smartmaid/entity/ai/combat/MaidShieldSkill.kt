package com.oyxdsg.smartmaid.entity.ai.combat

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack

object MaidShieldSkill {
    private const val OFFHAND_SLOT = 40
    private const val STORAGE_SIZE = 36

    @JvmStatic
    fun hasShield(maid: SmartMaidEntity): Boolean {
        val inv = maid.maidInventory
        for (i in 0 until inv.containerSize) if (isShield(inv.getItem(i))) return true
        return false
    }

    @JvmStatic
    fun equipShield(maid: SmartMaidEntity): Boolean {
        val inv = maid.maidInventory
        if (isShield(inv.getItem(OFFHAND_SLOT))) {
            maid.syncInventoryArmor()
            return true
        }
        var slot = -1
        for (i in 0 until STORAGE_SIZE) if (isShield(inv.getItem(i))) { slot = i; break }
        if (slot < 0) return false
        val old = inv.getItem(OFFHAND_SLOT)
        inv.setItem(OFFHAND_SLOT, inv.getItem(slot))
        inv.setItem(slot, old)
        inv.setChanged()
        maid.syncInventoryArmor()
        return true
    }

    @JvmStatic
    fun beginBlock(maid: SmartMaidEntity): Boolean {
        if (!isShield(maid.maidInventory.getItem(OFFHAND_SLOT))) return false
        if (maid.isUsingItem) maid.stopUsingItem()
        maid.startUsingItem(InteractionHand.OFF_HAND)
        return true
    }

    private fun isShield(stack: ItemStack): Boolean = !stack.isEmpty && stack.has(DataComponents.BLOCKS_ATTACKS)
}
