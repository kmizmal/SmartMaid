package com.oyxdsg.smartmaid.gui

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.network.chat.Component
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu

/**
 * 女仆背包菜单提供者：Shift+E 打开女仆背包时创建 [MaidInventoryMenu]。
 */
class MaidInventoryMenuProvider(private val maid: SmartMaidEntity) : MenuProvider {
    override fun getDisplayName(): Component =
        Component.translatable("gui.smartmaid.maid_inventory")

    override fun createMenu(syncId: Int, inv: Inventory, player: Player): AbstractContainerMenu =
        MaidInventoryMenu(syncId, inv, maid)
}
