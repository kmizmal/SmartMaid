package com.oyxdsg.smartmaid.client.gui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.oyxdsg.smartmaid.network.MaidSettingsPayload
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.ArrayList
import kotlin.math.max
import kotlin.math.min

/**
 * 二级「设置」页（N1）：把原来 9 项玩家绑定设置搬进新菜单。
 * 点击整行循环切换档位；客户端只发「字段 + 档位索引」给服务端权威修改，回推状态刷新。
 */
class MaidSettingsScreen(parent: Screen?, private var state: JsonObject?) : MaidSubScreen(parent, Component.literal("设置")) {

    private data class Row(val field: String, val label: String, val values: Array<String>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Row

            if (field != other.field) return false
            if (label != other.label) return false
            if (!values.contentEquals(other.values)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = field.hashCode()
            result = 31 * result + label.hashCode()
            result = 31 * result + values.contentHashCode()
            return result
        }
    }

    private val rects = ArrayList<IntArray>()
    private var hover: Int = -1

    fun updateState(json: String) {
        try {
            this.state = JsonParser.parseString(json).asJsonObject
        } catch (ignored: Exception) {
        }
    }

    override fun init() {
        super.init()
        ACTIVE = this
    }

    override fun removed() {
        super.removed()
        if (ACTIVE === this) {
            ACTIVE = null
        }
    }

    override fun renderContent(ex: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        this.rects.clear()
        var y = this.top + 36
        val rowW = PANEL_W - 32
        this.hover = -1
        for (i in ROWS.indices) {
            val row = ROWS[i]
            val rx = this.left + 16
            val hov = mouseX >= rx && mouseX < rx + rowW && mouseY >= y && mouseY < y + 17
            MaidTheme.roundRect(ex, rx, y, rx + rowW, y + 17, if (hov) MaidTheme.HOVER else MaidTheme.CARD, 0)
            ex.text(this.font, Component.literal(row.label), rx + 8, y + 5, MaidTheme.TEXT)
            val `val` = row.values[idx(row)]
            ex.text(this.font, Component.literal(`val`), rx + rowW - 8 - this.font.width(`val`), y + 5, MaidTheme.ACCENT)
            this.rects.add(intArrayOf(rx, y, rowW, 17))
            y += 18
        }
        ex.text(this.font, Component.literal("点击一行循环切换 · 设置与玩家绑定"),
            this.left + 16, this.top + PANEL_H - 16, MaidTheme.TEXT_3)
    }

    private fun idx(row: Row): Int {
        val s = this.state
        if (s != null && s.has("settings") && s.getAsJsonObject("settings").has(row.field)) {
            val v = s.getAsJsonObject("settings").get(row.field).asInt
            return max(0, min(v, row.values.size - 1))
        }
        return 0
    }

    override fun mouseClicked(event: MouseButtonEvent, focused: Boolean): Boolean {
        if (com.oyxdsg.smartmaid.compat.MaidCompat.isPrimaryMouseButton(event.button())) {
            for (i in this.rects.indices) {
                val r = this.rects[i]
                if (inside(event.x(), event.y(), r[0], r[1], r[2], r[3])) {
                    val row = ROWS[i]
                    val next = (idx(row) + 1) % row.values.size
                    MaidNet.send(MaidSettingsPayload(row.field, next))
                    return true
                }
            }
        }
        return super.mouseClicked(event, focused)
    }

    companion object {
        /** 当前活跃设置页（供 S2C 状态刷新定位）。 */
        @JvmStatic
        var ACTIVE: MaidSettingsScreen? = null

        private val ROWS: List<Row> = listOf(
            Row("friendlyFire", "友军伤害", arrayOf("关闭", "开启")),
            Row("followEnabled", "自动跟随", arrayOf("关闭", "开启")),
            Row("followStart", "跟随触发距离", arrayOf("2格", "3格", "5格", "8格", "12格")),
            Row("followStop", "跟随停止距离", arrayOf("6格", "10格", "15格", "24格", "40格")),
            Row("maxHealth", "最大生命", arrayOf("10", "20", "24", "40", "60", "100")),
            Row("hungerRate", "饱食消耗速度", arrayOf("0.5x", "1.0x", "1.5x", "2.0x", "3.0x")),
            Row("regenRate", "回血速度", arrayOf("0.5x", "1.0x", "1.5x", "2.0x", "3.0x")),
            Row("deathDrop", "死亡掉落", arrayOf("全部掉落", "保留装备", "不掉落")),
        )
    }
}
