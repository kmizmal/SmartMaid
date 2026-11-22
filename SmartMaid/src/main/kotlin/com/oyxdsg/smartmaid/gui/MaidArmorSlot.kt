package com.oyxdsg.smartmaid.gui

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.resources.Identifier
import net.minecraft.world.Container
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents
import net.minecraft.world.item.enchantment.EnchantmentHelper

/** 女仆装备槽：操作容器槽位并同步到女仆实体的真实装备栏。 */
class MaidArmorSlot(
    private val owner: SmartMaidEntity?,
    container: Container,
    private val slot: EquipmentSlot,
    index: Int,
    x: Int,
    y: Int,
    private val emptyIcon: Identifier?
) : net.minecraft.world.inventory.Slot(container, index, x, y) {

    override fun setByPlayer(newStack: ItemStack, oldStack: ItemStack) {
        owner?.onEquipItem(slot, oldStack, newStack)
        super.setByPlayer(newStack, oldStack)
        syncToOwner()
    }

    override fun set(stack: ItemStack) {
        super.set(stack)
        syncToOwner()
    }

    override fun getMaxStackSize(): Int =
        if (slot == EquipmentSlot.OFFHAND) super.getMaxStackSize() else 1

    override fun mayPlace(stack: ItemStack): Boolean {
        if (owner == null || slot == EquipmentSlot.OFFHAND) return true
        return owner.isEquippableInSlot(stack, slot)
    }

    override fun mayPickup(player: net.minecraft.world.entity.player.Player): Boolean {
        val stack = item
        if (owner != null && !stack.isEmpty && !player.isCreative
            && EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)
        ) {
            return false
        }
        return super.mayPickup(player)
    }

    override fun isActive(): Boolean = owner == null || owner.canUseSlot(slot)

    override fun getNoItemIcon(): Identifier? = emptyIcon

    private fun syncToOwner() {
        owner?.setItemSlot(slot, item)
    }
}
