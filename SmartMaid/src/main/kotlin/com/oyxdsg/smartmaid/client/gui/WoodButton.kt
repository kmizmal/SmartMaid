package com.oyxdsg.smartmaid.client.gui

import com.oyxdsg.smartmaid.SmartMaid
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

open class WoodButton(x: Int, y: Int, w: Int, h: Int, message: Component, onPress: Button.OnPress) :
    Button(x, y, w, h, message, onPress, DEFAULT_NARRATION) {
    companion object {
        private fun id(name: String) = Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "textures/gui/$name")
        private val wide = id("maid_button.png")
        private val wideHover = id("maid_button_hover.png")
        private val narrow = id("maid_button_s.png")
        private val narrowHover = id("maid_button_s_hover.png")
    }
    override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val small = width < 200
        val tex = if (small) { if (isHoveredOrFocused) narrowHover else narrow } else { if (isHoveredOrFocused) wideHover else wide }
        val texW = if (small) 118 else 244
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0f, 0f, texW, 20, texW, 20)
        extractDefaultLabel(g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE))
    }
}
