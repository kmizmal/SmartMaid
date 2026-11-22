package com.oyxdsg.smartmaid.client.gui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * 二级页基类（N1）：统一「世界遮罩 + 面板 + 标题栏 + 返回」链路。
 * 子类只实现 [renderContent]。
 */
abstract class MaidSubScreen protected constructor(
    @JvmField protected val parent: Screen?,
    title: Component,
) : Screen(title) {

    @JvmField
    protected var left: Int = 0

    @JvmField
    protected var top: Int = 0

    private var backX: Int = 0
    private var backY: Int = 0
    private var hoverBack: Boolean = false

    override fun init() {
        this.left = (this.width - PANEL_W) / 2
        this.top = (this.height - PANEL_H) / 2
        this.backX = this.left + 12
        this.backY = this.top + 8
    }

    override fun extractBackground(ex: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        if (this.minecraft != null && this.minecraft.level != null) {
            this.extractBlurredBackground(ex)
        }
        ex.fill(0, 0, this.width, this.height, MaidTheme.WORLD_OVERLAY)
    }

    override fun extractRenderState(ex: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        MaidTheme.roundRect(ex, this.left, this.top, this.left + PANEL_W, this.top + PANEL_H, MaidTheme.PANEL, 8)
        // 返回按钮
        MaidTheme.roundRect(
            ex, backX, backY, backX + BACK_W, backY + BACK_H,
            if (hoverBack) MaidTheme.HOVER_STRONG else MaidTheme.CARD, 6,
        )
        ex.centeredText(this.font, Component.literal("返回"), backX + BACK_W / 2, backY + 5, MaidTheme.TEXT_2)
        // 标题居中
        ex.centeredText(this.font, this.title, this.left + PANEL_W / 2, this.top + 10, MaidTheme.TEXT)
        // 分割线
        val lineY = this.top + 30
        ex.fill(this.left + 12, lineY, this.left + PANEL_W - 12, lineY + 1, MaidTheme.BORDER)
        renderContent(ex, mouseX, mouseY, tickDelta)
    }

    /** 子类在此绘制内容区（y 从 `top+36` 起）。 */
    protected abstract fun renderContent(ex: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float)

    override fun mouseClicked(event: MouseButtonEvent, focused: Boolean): Boolean {
        if (com.oyxdsg.smartmaid.compat.MaidCompat.isPrimaryMouseButton(event.button()) &&
            inside(event.x(), event.y(), backX, backY, BACK_W, BACK_H)
        ) {
            goBack()
            return true
        }
        return super.mouseClicked(event, focused)
    }

    override fun mouseMoved(mx: Double, my: Double) {
        this.hoverBack = inside(mx, my, backX, backY, BACK_W, BACK_H)
        super.mouseMoved(mx, my)
    }

    protected open fun goBack() {
        val p = this.parent
        if (this.minecraft != null && p != null) {
            this.minecraft!!.setScreenAndShow(p)
        }
    }

    override fun onClose() {
        goBack()
    }

    override fun isPauseScreen(): Boolean = false

    /**
     * **测试注入口**：右上/左上「返回」按钮的矩形 `{x,y,w,h}`。
     *
     * 给客户端自动化测试合成点击用（`screen.mouseClicked(...)`，不经真实输入设备）。
     * 生产路径不调用它。
     */
    fun backRectForTest(): IntArray = intArrayOf(backX, backY, BACK_W, BACK_H)

    protected companion object {
        @JvmField
        protected val PANEL_W: Int = 340

        @JvmField
        protected val PANEL_H: Int = 210

        @JvmField
        protected val BACK_W: Int = 44

        @JvmField
        protected val BACK_H: Int = 18

        @JvmStatic
        protected fun inside(mx: Double, my: Double, x: Int, y: Int, w: Int, h: Int): Boolean =
            mx >= x && mx < x + w && my >= y && my < y + h
    }
}
