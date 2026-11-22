package com.oyxdsg.smartmaid.gui

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.network.chat.Component
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu

/**
 * 女仆管理菜单提供者：Shift+右键 打开时创建 [MaidControlMenu]。
 */
class MaidControlMenuProvider(private val maid: SmartMaidEntity) : MenuProvider {
    override fun getDisplayName(): Component =
        Component.translatable("gui.smartmaid.maid_control")

    override fun createMenu(syncId: Int, inv: Inventory, player: Player): AbstractContainerMenu? =
        MaidControlMenu(syncId, inv, maid)
}
