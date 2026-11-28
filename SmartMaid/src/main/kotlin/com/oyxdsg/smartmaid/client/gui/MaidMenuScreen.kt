package com.oyxdsg.smartmaid.client.gui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.network.MaidCommandPayload
import com.oyxdsg.smartmaid.network.MaidMenuActionPayload
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EquipmentSlot

/**
 * 女仆主菜单（自绘）：3D 预览 + 5 个入口 + 状态条 + 4 个高频动作。
 */
class MaidMenuScreen(private val maidId: Int, food: Int) : Screen(Component.literal("女仆菜单")) {

    private var foodLive: Int = food
    private var state: JsonObject? = null
    private var queryTimer: Int = 0
    private var left: Int = 0
    private var top: Int = 0

    private val entryX = IntArray(5)
    private val entryY = IntArray(5)
    private val entryW = IntArray(5)
    private val entryH = IntArray(5)
    private val actX = IntArray(4)
    private val actY = IntArray(4)
    private val actW = IntArray(4)
    private val actH = IntArray(4)

    private var hoverEntry: Int = -1
    private var hoverAction: Int = -1
    private var toast: String = ""
    private var toastUntil: Long = 0

    /** 收到服务端状态快照（S2C）：更新饱食度与队列摘要。 */
    fun updateState(json: String) {
        try {
            this.state = JsonParser.parseString(json).asJsonObject
            val st = this.state!!
            if (st.has("food")) {
                this.foodLive = st.get("food").asInt
            }
            if (st.has("receipt_msg")) {
                val ok = !st.has("receipt_ok") || st.get("receipt_ok").asBoolean
                showToast((if (ok) "" else "[!] ") + st.get("receipt_msg").asString)
            }
        } catch (ignored: Exception) {
            // 解析失败保持原状态
        }
    }

    override fun tick() {
        super.tick()
        if (++this.queryTimer >= 20) {
            this.queryTimer = 0
            MaidNet.send(MaidMenuActionPayload.of("query"))
        }
    }

    override fun init() {
        ACTIVE = this
        this.left = (this.width - PANEL_W) / 2
        this.top = (this.height - PANEL_H) / 2
        val ex = this.left + 162
        val ey = this.top + 36
        val ew = PANEL_W - 162 - 16
        val eh = 22
        for (i in 0 until 5) {
            entryX[i] = ex
            entryY[i] = ey + i * (eh + 4)
            entryW[i] = ew
            entryH[i] = eh
        }
        val gap = 6
        val aw = (PANEL_W - 32 - 3 * gap) / 4
        val ay = this.top + PANEL_H - 30
        for (i in 0 until 4) {
            actX[i] = this.left + 16 + i * (aw + gap)
            actY[i] = ay
            actW[i] = aw
            actH[i] = 20
        }
    }

    override fun extractBackground(ex: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        if (this.minecraft != null && this.minecraft.level != null) {
            this.extractBlurredBackground(ex)
        }
        ex.fill(0, 0, this.width, this.height, MaidTheme.WORLD_OVERLAY)
    }

    override fun extractRenderState(ex: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        MaidTheme.roundRect(ex, this.left, this.top, this.left + PANEL_W, this.top + PANEL_H, MaidTheme.PANEL, 0)
        ex.centeredText(this.font, Component.literal("女仆菜单"), this.left + PANEL_W / 2, this.top + 10, MaidTheme.TEXT)
        ex.fill(this.left + 12, this.top + 26, this.left + PANEL_W - 12, this.top + 27, MaidTheme.BORDER)

        val maid = getMaid()

        // 左：3D 预览（放大框 + 缩小模型，避免截断）
        val px1 = this.left + 16
        val py1 = this.top + 34
        val px2 = this.left + 150
        val py2 = this.top + 156
        MaidTheme.roundRect(ex, px1, py1, px2, py2, MaidTheme.CARD, 0)
        if (maid != null) {
            InventoryScreen.extractEntityInInventoryFollowsMouse(ex, px1, py1, px2, py2,
                46, 0.0625f, mouseX.toFloat(), mouseY.toFloat(), maid)
            ex.centeredText(this.font, Component.literal(maid.name.string),
                (px1 + px2) / 2, py2 + 2, MaidTheme.TEXT_2)
            drawEquipment(ex, maid)
        } else {
            ex.centeredText(this.font, Component.literal("（女仆未加载）"),
                (px1 + px2) / 2, (py1 + py2) / 2, MaidTheme.TEXT_3)
        }

        // 右：5 入口
        for (i in 0 until 5) {
            MaidTheme.roundRect(ex, entryX[i], entryY[i], entryX[i] + entryW[i], entryY[i] + entryH[i],
                if (hoverEntry == i) MaidTheme.HOVER else MaidTheme.CARD, 0)
            ex.text(this.font, Component.literal(ENTRY_KEYS[i]), entryX[i] + 12, entryY[i] + 7, MaidTheme.TEXT)
            ex.text(this.font, Component.literal(entrySub(i)), entryX[i] + 64, entryY[i] + 7, MaidTheme.TEXT_3)
        }

        // 状态条：生命爱心 + 饱食 + 坐姿
        val sy = this.top + PANEL_H - 58
        MaidTheme.roundRect(ex, this.left + 16, sy, this.left + PANEL_W - 16, sy + 20, MaidTheme.TRACK, 0)
        val hp = if (maid == null) 0 else Math.ceil(maid.health.toDouble()).toInt()
        val maxHp = if (maid == null) 20 else Math.max(1, Math.ceil(maid.maxHealth.toDouble()).toInt())
        val hpHalf = Math.round(20.0 * hp / maxHp).toInt() // 20 个半心单位 = 10 心
        for (i in 0 until 10) {
            drawHeart(ex, this.left + 24 + i * 8, sy + 6, hpHalf - i * 2)
        }
        val foodHalf = Math.max(0, Math.min(20, foodLive))
        for (i in 0 until 10) {
            drawFood(ex, this.left + 130 + i * 8, sy + 6, foodHalf - i * 2)
        }
        val stateStr = if (maid == null) "未加载" else (if (maid.isOrderedToSit) "坐下待命" else "跟随中")
        ex.text(this.font, Component.literal(stateStr),
            this.left + PANEL_W - 16 - this.font.width(stateStr) - 8, sy + 7, MaidTheme.TEXT_2)

        // 4 高频动作
        for (i in 0 until 4) {
            MaidTheme.roundRect(ex, actX[i], actY[i], actX[i] + actW[i], actY[i] + actH[i],
                if (hoverAction == i) MaidTheme.BTN_HOVER else MaidTheme.BTN, 0)
            ex.centeredText(this.font, Component.literal(ACTION_KEYS[i]),
                actX[i] + actW[i] / 2, actY[i] + 6, MaidTheme.CARD)
        }

        if (toast.isNotEmpty() && System.currentTimeMillis() < toastUntil) {
            ex.centeredText(this.font, Component.literal(toast), this.left + PANEL_W / 2, this.top + PANEL_H + 6, MaidTheme.TEXT)
        }
    }

    /** 爱心：v>=2 满、v==1 半、否则空心。复用原版 HUD 精灵。 */
    private fun drawHeart(ex: GuiGraphicsExtractor, x: Int, y: Int, v: Int) {
        ex.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, HEART_BG, x, y, 9, 9)
        if (v >= 2) {
            ex.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, HEART_FULL, x, y, 9, 9)
        } else if (v == 1) {
            ex.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, HEART_HALF, x, y, 9, 9)
        }
    }

    /** 饱食度：复用原版 HUD 精灵（food_empty/full/half）。 */
    private fun drawFood(ex: GuiGraphicsExtractor, x: Int, y: Int, v: Int) {
        ex.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, FOOD_BG, x, y, 9, 9)
        if (v >= 2) {
            ex.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, FOOD_FULL, x, y, 9, 9)
        } else if (v == 1) {
            ex.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, FOOD_HALF, x, y, 9, 9)
        }
    }

    private fun entrySub(i: Int): String {
        val st = this.state
        if (i == 0 && st != null) {
            val n = (if (st.has("short_size")) st.get("short_size").asInt else 0) +
                (if (st.has("long_size")) st.get("long_size").asInt else 0)
            return "队列 $n 项"
        }
        return ENTRY_SUBS[i]
    }

    private fun getMaid(): SmartMaidEntity? {
        val mc = this.minecraft
        if (mc?.level == null) {
            return null
        }
        val level = mc.level ?: return null
        val e = level.getEntity(this.maidId)
        return e as? SmartMaidEntity
    }

    override fun mouseClicked(event: MouseButtonEvent, focused: Boolean): Boolean {
        if (!com.oyxdsg.smartmaid.compat.MaidCompat.isPrimaryMouseButton(event.button())) {
            return super.mouseClicked(event, focused)
        }
        val mx = event.x()
        val my = event.y()
        for (i in 0 until 5) {
            if (inside(mx, my, entryX[i], entryY[i], entryW[i], entryH[i])) {
                onEntry(i)
                return true
            }
        }
        for (i in 0 until 4) {
            if (inside(mx, my, actX[i], actY[i], actW[i], actH[i])) {
                onAction(i)
                return true
            }
        }
        return super.mouseClicked(event, focused)
    }

    override fun mouseMoved(mx: Double, my: Double) {
        hoverEntry = -1
        hoverAction = -1
        for (i in 0 until 5) {
            if (inside(mx, my, entryX[i], entryY[i], entryW[i], entryH[i])) {
                hoverEntry = i
            }
        }
        for (i in 0 until 4) {
            if (inside(mx, my, actX[i], actY[i], actW[i], actH[i])) {
                hoverAction = i
            }
        }
        super.mouseMoved(mx, my)
    }

    /** 装备概览：4 盔甲 + 主手 + 副手（客户端直接读实体，零同步）。 */
    private fun drawEquipment(ex: GuiGraphicsExtractor, maid: SmartMaidEntity) {
        val cell = 20
        val total = EQUIP.size * cell
        val x0 = this.left + (PANEL_W - total) / 2
        val y = this.top + 168
        for (i in EQUIP.indices) {
            val x = x0 + i * cell
            MaidTheme.roundRect(ex, x, y, x + cell - 2, y + cell - 2, MaidTheme.CARD, 0)
            val st = maid.getItemBySlot(EQUIP[i])
            if (!st.isEmpty) {
                ex.item(st, x + 1, y + 1)
            }
        }
    }

    /**
     * **测试注入口**：第 `i` 个入口按钮的矩形 `{x,y,w,h}`（越界返回 `null`）。
     */
    fun entryRectForTest(i: Int): IntArray? =
        if (i >= 0 && i < entryX.size) intArrayOf(entryX[i], entryY[i], entryW[i], entryH[i]) else null

    /** **测试注入口**：第 `i` 个高频动作按钮的矩形（越界返回 `null`）。 */
    fun actionRectForTest(i: Int): IntArray? =
        if (i >= 0 && i < actX.size) intArrayOf(actX[i], actY[i], actW[i], actH[i]) else null

    private fun onEntry(i: Int) {
        when (i) {
            0 -> this.minecraft.setScreenAndShow(MaidTaskScreen(this, this.state))
            1 -> {
                MaidNet.send(MaidCommandPayload(MaidCommandPayload.ACTION_OPEN_INVENTORY))
                this.onClose()
            }
            2 -> this.minecraft.setScreenAndShow(MaidPlaceholderScreen(this, "对话"))
            3 -> this.minecraft.setScreenAndShow(MaidSettingsScreen(this, this.state))
            4 -> this.minecraft.setScreenAndShow(MaidPlaceholderScreen(this, "动作"))
        }
    }

    private fun onAction(i: Int) {
        val maid = getMaid()
        when (i) {
            0 -> {
                if (maid != null && maid.isOrderedToSit) {
                    sendCmd(MaidCommandPayload.ACTION_TOGGLE_SIT)
                }
                showToast("跟随中")
            }
            1 -> {
                if (maid == null || !maid.isOrderedToSit) {
                    sendCmd(MaidCommandPayload.ACTION_TOGGLE_SIT)
                }
                showToast("坐下待命")
            }
            2 -> {
                sendCmd(MaidCommandPayload.ACTION_RECALL)
                showToast("已召回")
            }
            3 -> {
                MaidNet.send(MaidMenuActionPayload.of("cancelCurrent"))
                showToast("已停止当前任务")
            }
        }
    }

    private fun sendCmd(action: Int) {
        MaidNet.send(MaidCommandPayload(action))
    }

    private fun showToast(s: String) {
        this.toast = s
        this.toastUntil = System.currentTimeMillis() + 1500
    }

    override fun removed() {
        super.removed()
        if (ACTIVE === this) {
            ACTIVE = null
        }
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        private const val PANEL_W = 360
        private const val PANEL_H = 252

        private val EQUIP = arrayOf(
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND,
        )

        private val ENTRY_KEYS = arrayOf("任务", "背包", "对话", "设置", "动作")
        private val ENTRY_SUBS = arrayOf("队列管理", "41 格 · 装备", "与女仆聊天", "跟随 · 护主", "表情动作")
        private val ACTION_KEYS = arrayOf("跟随我", "坐下待命", "召回", "停止当前")

        private val HEART_BG: Identifier = Identifier.withDefaultNamespace("hud/heart/container")
        private val HEART_FULL: Identifier = Identifier.withDefaultNamespace("hud/heart/full")
        private val HEART_HALF: Identifier = Identifier.withDefaultNamespace("hud/heart/half")
        private val FOOD_BG: Identifier = Identifier.withDefaultNamespace("hud/food_empty")
        private val FOOD_FULL: Identifier = Identifier.withDefaultNamespace("hud/food_full")
        private val FOOD_HALF: Identifier = Identifier.withDefaultNamespace("hud/food_half")

        /** 当前活跃的主菜单实例（供 S2C 状态刷新定位；26.2 无可访问的 Minecraft.screen）。 */
        @JvmStatic
        var ACTIVE: MaidMenuScreen? = null

        private fun inside(mx: Double, my: Double, x: Int, y: Int, w: Int, h: Int): Boolean =
            mx >= x && mx < x + w && my >= y && my < y + h
    }
}
