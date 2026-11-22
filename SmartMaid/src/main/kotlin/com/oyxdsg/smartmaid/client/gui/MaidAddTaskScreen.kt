package com.oyxdsg.smartmaid.client.gui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.network.MaidMenuActionPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.ArrayList

/**
 * 二级「添加任务」页（N1 步骤 4）：任务网格 + 就地参数面板（数字 stepper / 坐标三态「准星·女仆脚下」）。
 */
class MaidAddTaskScreen(parent: Screen?, state: JsonObject?) :
    MaidSubScreen(parent, Component.literal("添加任务")) {

    /** 参数规格：键、默认、上下限。 */
    private data class Spec(val key: String, val def: Int, val min: Int, val max: Int)

    /** 快捷任务：显示名、指令、数字参数、是否需要坐标。 */
    private data class Quick(val label: String, val cmd: String, val specs: List<Spec>, val needsPos: Boolean)

    private data class Hot(val x: Int, val y: Int, val w: Int, val h: Int, val action: Runnable)

    private val hots = ArrayList<Hot>()
    private var selected: Int = -1
    private val values = IntArray(4)
    private var posMode: Int = 0 // 0 = 准星，1 = 女仆脚下

    override fun renderContent(ex: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        this.hots.clear()
        if (selected < 0) {
            renderGrid(ex)
        } else {
            renderParams(ex, QUICKS[selected])
        }
    }

    private fun renderGrid(ex: GuiGraphicsExtractor) {
        ex.text(this.font, Component.literal("点选任务，再设参数追加到短期队列"),
            this.left + 16, this.top + 38, MaidTheme.TEXT_3)
        val x = this.left + 16
        val y = this.top + 56
        val w = (PANEL_W - 32 - 8) / 2
        val h = 24
        for (i in QUICKS.indices) {
            val cx = x + (i % 2) * (w + 8)
            val cy = y + (i / 2) * (h + 8)
            val idx = i
            MaidTheme.roundRect(ex, cx, cy, cx + w, cy + h, MaidTheme.CARD, 0)
            ex.text(this.font, Component.literal(QUICKS[i].label), cx + 10, cy + 8, MaidTheme.TEXT)
            this.hots.add(Hot(cx, cy, w, h, Runnable { select(idx) }))
        }
    }

    private fun select(idx: Int) {
        this.selected = idx
        val specs = QUICKS[idx].specs
        for (i in values.indices) {
            values[i] = if (i < specs.size) specs[i].def else 0
        }
        this.posMode = 0
    }

    private fun renderParams(ex: GuiGraphicsExtractor, q: Quick) {
        var y = this.top + 40
        ex.text(this.font, Component.literal("任务：" + q.label), this.left + 16, y, MaidTheme.TEXT)
        y += 18
        val specs = q.specs
        for (i in specs.indices) {
            val s = specs[i]
            ex.text(this.font, Component.literal(s.key), this.left + 16, y + 5, MaidTheme.TEXT_2)
            val bx = this.left + 110
            val idx = i
            button(ex, bx, y, 20, "-", Runnable {
                values[idx] = Math.max(s.min, values[idx] - step(s))
            })
            ex.text(this.font, Component.literal(values[i].toString()), bx + 30, y + 6, MaidTheme.TEXT)
            button(ex, bx + 56, y, 20, "+", Runnable {
                values[idx] = Math.min(s.max, values[idx] + step(s))
            })
            y += 26
        }

        if (q.needsPos) {
            ex.text(this.font, Component.literal("坐标"), this.left + 16, y + 5, MaidTheme.TEXT_2)
            val bx = this.left + 110
            button(ex, bx, y, 60, if (posMode == 0) "[准星]✓" else "准星", Runnable { posMode = 0 })
            button(ex, bx + 66, y, 78, if (posMode == 1) "[女仆脚下]✓" else "女仆脚下", Runnable { posMode = 1 })
            y += 26
            if (posMode == 0 && crosshairBlock() == null) {
                ex.text(this.font, Component.literal("（准星未指向方块）"), this.left + 16, y, MaidTheme.TEXT_3)
                y += 14
            }
        }

        val by = this.top + PANEL_H - 26
        button(ex, this.left + 16, by, 84, "取消", Runnable { this.selected = -1 })
        button(ex, this.left + PANEL_W - 16 - 84, by, 84, "追加到队列", Runnable { submit() })
    }

    private fun submit() {
        val q = QUICKS[this.selected]
        val p = JsonObject()
        val specs = q.specs
        for (i in specs.indices) {
            p.addProperty(specs[i].key, values[i])
        }
        if (q.needsPos) {
            if (posMode == 1) {
                val a = JsonArray()
                a.add("~")
                a.add("~")
                a.add("~")
                p.add("pos", a)
            } else {
                val b = crosshairBlock() ?: return
                val a = JsonArray()
                a.add(b[0])
                a.add(b[1])
                a.add(b[2])
                p.add("pos", a)
            }
        }
        MaidNet.send(MaidMenuActionPayload.enqueue(q.cmd, p.toString()))
        goBack()
    }

    private fun button(ex: GuiGraphicsExtractor, x: Int, y: Int, w: Int, label: String, action: Runnable) {
        MaidTheme.roundRect(ex, x, y, x + w, y + 20, MaidTheme.BTN, 0)
        ex.centeredText(this.font, Component.literal(label), x + w / 2, y + 6, MaidTheme.CARD)
        this.hots.add(Hot(x, y, w, 20, action))
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
        private val QUICKS: List<Quick> = listOf(
            Quick("挖矿", "mine", listOf(Spec("range", 12, 1, 32), Spec("count", 8, 1, 64)), false),
            Quick("护卫", "guard", listOf(Spec("range", 10, 1, 32)), false),
            Quick("收集", "collect", listOf(Spec("range", 8, 1, 32)), false),
            Quick("进食", "eat", listOf(), false),
            Quick("攻击最近", "attack", listOf(Spec("range", 12, 1, 32)), false),
            Quick("移动", "move", listOf(), true),
            Quick("耕作", "farm", listOf(Spec("range", 4, 1, 16)), true),
            Quick("存箱", "cheststore", listOf(Spec("count", 64, 1, 64)), true),
        )

        private fun step(s: Spec): Int = if ((s.max - s.min) >= 32) 4 else 1

        /** 准星所指方块（客户端射线，超出 8 格或未命中返回 null）。 */
        private fun crosshairBlock(): IntArray? {
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return null
            val from = player.getEyePosition()
            val to = from.add(player.getViewVector(1.0f).scale(8.0))
            val hit = player.level().clip(
                ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
            )
            if (hit == null || hit.type == HitResult.Type.MISS) {
                return null
            }
            val bp = hit.blockPos
            return intArrayOf(bp.x, bp.y, bp.z)
        }
    }
}
