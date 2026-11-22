package com.oyxdsg.smartmaid.client.gui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

open class MaidPlaceholderScreen(parent: Screen, private val label: String) : MaidSubScreen(parent, Component.literal(label)) {
    override fun renderContent(ex: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        val cx = left + PANEL_W / 2
        ex.centeredText(font, Component.literal(label), cx, top + PANEL_H / 2 - 12, MaidTheme.TEXT)
        ex.centeredText(font, Component.literal("该页在 N1 为占位，后续版本实现"), cx, top + PANEL_H / 2 + 4, MaidTheme.TEXT_3)
    }
}
