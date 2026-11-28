package com.oyxdsg.smartmaid.client.gui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.gui.MaidInventoryMenu

/**
 * 女仆背包界面：布局与渲染照搬原版玩家背包界面（InventoryScreen）——
 * 用标准容器背景 [AbstractContainerScreen.INVENTORY_LOCATION]，槽位位置由 MaidInventoryMenu 照搬原版坐标。
 * 左上角渲染女仆自身缩小模型（跟随鼠标旋转，与原版玩家背包一致）。
 */
class MaidInventoryScreen(menu: MaidInventoryMenu, inv: Inventory, title: Component) :
    AbstractContainerScreen<MaidInventoryMenu>(menu, inv, title) {

    private var xMouse: Float = 0f
    private var yMouse: Float = 0f

    init {
        // 标题/背包标签位置（照搬原版布局）
        this.titleLabelX = 8
        this.titleLabelY = 6
        this.inventoryLabelX = 8
        this.inventoryLabelY = this.imageHeight - 92
    }

    override fun extractRenderState(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        super.extractRenderState(extractor, mouseX, mouseY, tickDelta)
        this.xMouse = mouseX.toFloat()
        this.yMouse = mouseY.toFloat()
    }

    override fun extractBackground(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        super.extractBackground(extractor, mouseX, mouseY, tickDelta)
        // 画标准容器背景（照搬原版 InventoryScreen.extractBackground）
        extractor.blit(
            RenderPipelines.GUI_TEXTURED, INVENTORY_LOCATION,
            this.leftPos, this.topPos, 0.0f, 0.0f, this.imageWidth, this.imageHeight, 256, 256,
        )

        // 左上角渲染女仆缩小模型（照搬原版玩家背包的位置与跟随鼠标旋转）
        val level = this.minecraft.level
        val maid = level?.getEntity(this.menu.getMaidId())
        if (maid is SmartMaidEntity) {
            InventoryScreen.extractEntityInInventoryFollowsMouse(
                extractor,
                this.leftPos + 26, this.topPos + 8,
                this.leftPos + 75, this.topPos + 78,
                30, 0.0625f, this.xMouse, this.yMouse, maid,
            )
        }
    }

    override fun extractLabels(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        extractor.text(this.font, this.title, this.leftPos + this.titleLabelX, this.topPos + this.titleLabelY, 0x404040)
    }
}
