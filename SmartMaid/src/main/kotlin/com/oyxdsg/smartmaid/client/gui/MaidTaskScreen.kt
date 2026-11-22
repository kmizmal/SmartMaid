package com.oyxdsg.smartmaid.client.gui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.oyxdsg.smartmaid.network.MaidMenuActionPayload
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.ArrayList

/**
 * 二级「任务」页（N1 步骤 4）：长期/短期两个队列的前端。
 *
 * 显示当前执行项 + 两队列，行内提供 停止这一项 / 移除 / 上移 / 停止（长期）；底部 清空短期 / 添加任务。
 */
class MaidTaskScreen(parent: Screen?, initialState: JsonObject?) : MaidSubScreen(parent, Component.literal("任务")) {

    private data class Hot(val x: Int, val y: Int, val w: Int, val h: Int, val action: Runnable)

    private val hots = ArrayList<Hot>()
    private var state: JsonObject? = initialState
    private var queryTimer: Int = 0
    private var toast: String = ""
    private var toastUntil: Long = 0

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

    fun updateState(json: String) {
        try {
            this.state = JsonParser.parseString(json).asJsonObject
            val st = this.state!!
            if (st.has("receipt_msg")) {
                val ok = !st.has("receipt_ok") || st.get("receipt_ok").asBoolean
                this.toast = (if (ok) "" else "[!] ") + st.get("receipt_msg").asString
                this.toastUntil = System.currentTimeMillis() + 1500
            }
        } catch (ignored: Exception) {
        }
    }

    override fun tick() {
        super.tick()
        if (++this.queryTimer >= 20) {
            this.queryTimer = 0
            MaidNet.send(MaidMenuActionPayload.of("query"))
        }
    }

    override fun renderContent(ex: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        this.hots.clear()
        val st = this.state
        val cur = if (st != null && st.has("current") && st.get("current").isJsonObject)
            st.getAsJsonObject("current") else null

        // 正在执行（固定区）
        if (cur != null) {
            drawSectionTitle(ex, "正在执行", this.top + 34)
            drawRow(ex, this.top + 46, cur, false, true)
        }

        // 长期队列（固定区，预留 2 行）
        val yLong = this.top + 76
        drawSectionTitle(ex, "长期队列 · 短期清空后才执行", yLong)
        val longs = arrayOf("long")
        if (longs.isEmpty) {
            ex.text(this.font, Component.literal("（空）"), this.left + 22, yLong + 13, MaidTheme.TEXT_3)
        } else {
            for (i in 0 until Math.min(longs.size(), 2)) {
                drawRow(ex, yLong + 12 + i * ROW_H, longs.get(i).asJsonObject, true, false)
            }
        }

        // 短期队列（固定区，预留 3 行）
        val yShort = this.top + 130
        drawSectionTitle(ex, "短期队列 · 优先执行", yShort)
        val shorts = arrayOf("short")
        if (shorts.isEmpty) {
            ex.text(this.font, Component.literal("（空）"), this.left + 22, yShort + 13, MaidTheme.TEXT_3)
        } else {
            renderGroupedShort(ex, shorts, yShort + 12)
        }

        // 底部按钮
        val by = this.top + PANEL_H - 26
        val bw = 84
        button(ex, this.left + 16, by, bw, "清空短期") {
            MaidNet.send(MaidMenuActionPayload.op("clear", "", "SHORT"))
        }
        button(ex, this.left + 16 + bw + 8, by, bw, "清空长期") {
            MaidNet.send(MaidMenuActionPayload.op("clear", "", "LONG"))
        }
        button(ex, this.left + PANEL_W - 16 - bw, by, bw, "＋ 添加任务") {
            this.minecraft?.setScreenAndShow(MaidAddTaskScreen(this, this.state))
        }

        if (this.toast.isNotEmpty() && System.currentTimeMillis() < this.toastUntil) {
            ex.centeredText(this.font, Component.literal(this.toast),
                this.left + PANEL_W / 2, this.top + PANEL_H - 42, MaidTheme.TEXT_2)
        }
    }

    private fun drawSectionTitle(ex: GuiGraphicsExtractor, s: String, y: Int) {
        ex.text(this.font, Component.literal(s), this.left + 16, y, MaidTheme.TEXT_2)
    }

    private fun drawRow(ex: GuiGraphicsExtractor, y: Int, item: JsonObject, isLong: Boolean, isCurrent: Boolean) {
        val id = str(item, "id")
        val name = str(item, "name")
        val st = str(item, "state")
        val interrupts = if (item.has("interrupts")) item.get("interrupts").asInt else 0
        MaidTheme.roundRect(ex, this.left + 16, y, this.left + PANEL_W - 16, y + ROW_H - 2, MaidTheme.CARD, 0)
        val label = if (name.isEmpty()) id else name
        val status = when {
            "RUNNING" == st -> "进行中"
            "PAUSED" == st -> "暂停中" + (if (interrupts > 0) "·打断$interrupts" else "")
            else -> "排队中"
        }
        ex.text(this.font, Component.literal("$label  ·  $status"), this.left + 22, y + 4, MaidTheme.TEXT)

        var bx = this.left + PANEL_W - 16 - 4
        if (isCurrent) {
            bx -= 56
            button(ex, bx, y, 52, "停止这一项") {
                MaidNet.send(MaidMenuActionPayload.of("cancelCurrent"))
            }
        } else if (isLong) {
            bx -= 34
            button(ex, bx, y, 30, "停止") {
                MaidNet.send(MaidMenuActionPayload.op("stop", id, "LONG"))
            }
            bx -= 34
            button(ex, bx, y, 30, "设当前") {
                MaidNet.send(MaidMenuActionPayload.op("promote", id, "LONG"))
            }
            bx -= 26
            button(ex, bx, y, 22, "↑") {
                MaidNet.send(MaidMenuActionPayload.op("moveUp", id, "LONG"))
            }
        } else {
            bx -= 26
            button(ex, bx, y, 22, "×") {
                MaidNet.send(MaidMenuActionPayload.op("remove", id, "SHORT"))
            }
            bx -= 26
            button(ex, bx, y, 22, "↑") {
                MaidNet.send(MaidMenuActionPayload.op("moveUp", id, "SHORT"))
            }
        }
    }

    /** 短期队列：同 group 折叠成一行（「烧所有矿物」N 项）+ 整组停止（§8.5）。 */
    private fun renderGroupedShort(ex: GuiGraphicsExtractor, shorts: JsonArray, y0: Int) {
        val doneGroups = HashSet<String>()
        var rows = 0
        var y = y0
        var i = 0
        while (i < shorts.size() && rows < 3) {
            val item = shorts.get(i).asJsonObject
            val g = str(item, "group")
            if (g.isNotEmpty()) {
                if (doneGroups.contains(g)) {
                    i++
                    continue
                }
                doneGroups.add(g)
                var n = 0
                for (j in 0 until shorts.size()) {
                    if (g == str(shorts.get(j).asJsonObject, "group")) {
                        n++
                    }
                }
                var label = str(item, "groupLabel")
                if (label.isEmpty()) {
                    label = g
                }
                drawGroupRow(ex, y, "「$label」 $n 项", g)
            } else {
                drawRow(ex, y, item, false, false)
            }
            y += ROW_H
            rows++
            i++
        }
    }

    private fun drawGroupRow(ex: GuiGraphicsExtractor, y: Int, label: String, groupId: String) {
        MaidTheme.roundRect(ex, this.left + 16, y, this.left + PANEL_W - 16, y + ROW_H - 2, MaidTheme.CARD, 0)
        ex.text(this.font, Component.literal(label), this.left + 22, y + 4, MaidTheme.TEXT)
        val bx = this.left + PANEL_W - 16 - 4 - 60
        button(ex, bx, y, 56, "整组停止") {
            MaidNet.send(MaidMenuActionPayload.op("stopGroup", groupId, ""))
        }
    }

    private fun button(ex: GuiGraphicsExtractor, x: Int, y: Int, w: Int, label: String, action: Runnable) {
        MaidTheme.roundRect(ex, x, y, x + w, y + ROW_H - 2, MaidTheme.BTN, 0)
        ex.centeredText(this.font, Component.literal(label), x + w / 2, y + 4, MaidTheme.CARD)
        this.hots.add(Hot(x, y, w, ROW_H - 2, action))
    }

    private fun arrayOf(key: String): JsonArray {
        val st = this.state
        if (st == null || !st.has(key) || !st.get(key).isJsonArray) {
            return JsonArray()
        }
        return st.getAsJsonArray(key)
    }

    override fun mouseClicked(event: MouseButtonEvent, focused: Boolean): Boolean {
        if (com.oyxdsg.smartmaid.compat.MaidCompat.isPrimaryMouseButton(event.button())) {
            for (h in this.hots) {
                if (inside(event.x(), event.y(), h.x, h.y, h.w, h.h)) {
                    h.action.run()
                    return true
                }
            }
        }
        return super.mouseClicked(event, focused)
    }

    companion object {
        private const val ROW_H = 18

        /** 当前活跃的任务页（供 S2C 状态刷新定位）。 */
        @JvmStatic
        var ACTIVE: MaidTaskScreen? = null

        private fun str(o: JsonObject, key: String): String =
            if (o.has(key) && !o.get(key).isJsonNull) o.get(key).asString else ""
    }
}
