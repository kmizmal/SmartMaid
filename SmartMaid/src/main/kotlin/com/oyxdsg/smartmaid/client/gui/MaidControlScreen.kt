package com.oyxdsg.smartmaid.client.gui

import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.gui.MaidControlMenu
import com.oyxdsg.smartmaid.network.MaidCommandPayload
import com.oyxdsg.smartmaid.network.MaidSettingsPayload
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory

/**
 * 女仆管理 / 设置界面（Shift+右键 打开）。
 *
 * 暖棕木质风：木纹面板 + 金色四角 + 木质按钮。单列，每行「设置名：当前值」，点击循环切换。
 * 所有文字用原版 [StringWidget] / 按钮自身承载（避免 extract 坐标问题）。
 */
class MaidControlScreen(menu: MaidControlMenu, inv: Inventory, title: Component) :
    AbstractContainerScreen<MaidControlMenu>(menu, inv, title) {

    private data class SettingRow(
        val field: String,
        val dataIndex: Int,
        val label: String,
        val values: Array<String>,
        val button: WoodButton,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as SettingRow

            if (dataIndex != other.dataIndex) return false
            if (field != other.field) return false
            if (label != other.label) return false
            if (!values.contentEquals(other.values)) return false
            if (button != other.button) return false

            return true
        }

        override fun hashCode(): Int {
            var result = dataIndex
            result = 31 * result + field.hashCode()
            result = 31 * result + label.hashCode()
            result = 31 * result + values.contentHashCode()
            result = 31 * result + button.hashCode()
            return result
        }
    }

    private val settingRows = ArrayList<SettingRow>()
    private var stateWidget: StringWidget? = null
    private var hintWidget: StringWidget? = null

    override fun init() {
        super.init()
        this.settingRows.clear()

        val x = this.leftPos + 12
        val w = PANEL_W - 24
        var y = this.topPos + 42
        val step = 22

        y = this.addRow("friendlyFire", MaidControlMenu.DATA_FRIENDLY_FIRE, "友军伤害",
            arrayOf("关闭", "开启"), x, y, w, step)
        y = this.addRow("followEnabled", MaidControlMenu.DATA_FOLLOW_ENABLED, "自动跟随",
            arrayOf("关闭", "开启"), x, y, w, step)
        y = this.addRow("followStart", MaidControlMenu.DATA_FOLLOW_START, "跟随触发距离",
            arrayOf("2格", "3格", "5格", "8格", "12格"), x, y, w, step)
        y = this.addRow("followStop", MaidControlMenu.DATA_FOLLOW_STOP, "跟随停止距离",
            arrayOf("6格", "10格", "15格", "24格", "40格"), x, y, w, step)
        y = this.addRow("protectMode", MaidControlMenu.DATA_PROTECT_MODE, "护主模式",
            arrayOf("主动护主", "被动护主", "关闭"), x, y, w, step)
        y = this.addRow("maxHealth", MaidControlMenu.DATA_MAX_HEALTH, "最大生命",
            arrayOf("10", "20", "24", "40", "60", "100"), x, y, w, step)
        y = this.addRow("hungerRate", MaidControlMenu.DATA_HUNGER_RATE, "饱食消耗速度",
            arrayOf("0.5x", "1.0x", "1.5x", "2.0x", "3.0x"), x, y, w, step)
        y = this.addRow("regenRate", MaidControlMenu.DATA_REGEN_RATE, "回血速度",
            arrayOf("0.5x", "1.0x", "1.5x", "2.0x", "3.0x"), x, y, w, step)
        this.addRow("deathDrop", MaidControlMenu.DATA_DEATH_DROP, "死亡掉落",
            arrayOf("全部掉落", "保留装备", "不掉落"), x, y, w, step)

        // 标题 / 状态 / 提示：用 StringWidget 承载，手动居中
        this.centered(StringWidget(this.leftPos, this.topPos + 6, 0, 12,
            Component.literal("女仆管理"), this.font))
        this.stateWidget = StringWidget(this.leftPos, this.topPos + 24, 0, 12,
            Component.literal(""), this.font)
        this.centeredWidget(this.stateWidget!!)
        this.addRenderableWidget(this.stateWidget!!)
        this.hintWidget = StringWidget(this.leftPos, this.topPos + PANEL_H - 16, 0, 12,
            Component.literal("点击按钮切换 · 设置与玩家绑定"), this.font)
        this.centeredWidget(this.hintWidget!!)
        this.addRenderableWidget(this.hintWidget!!)

        // 底部控制
        val btnY = this.topPos + PANEL_H - 38
        val half = (w - 8) / 2
        this.addRenderableWidget(WoodButton(x, btnY, half, 20,
            Component.literal("坐下 / 站起")) { this.send(MaidCommandPayload.ACTION_TOGGLE_SIT) })
        this.addRenderableWidget(WoodButton(x + half + 8, btnY, half, 20,
            Component.literal("召回")) { this.send(MaidCommandPayload.ACTION_RECALL) })
    }

    /** 创建并居中一个标题用 StringWidget。 */
    private fun centered(widget: StringWidget) {
        this.centeredWidget(widget)
        this.addRenderableWidget(widget)
    }

    private fun centeredWidget(widget: StringWidget) {
        val textW = this.font.width(widget.message)
        widget.width = textW
        widget.x = this.leftPos + (PANEL_W - textW) / 2
    }

    private fun addRow(
        field: String, dataIndex: Int, label: String, values: Array<String>,
        x: Int, y: Int, w: Int, step: Int,
    ): Int {
        val holder = arrayOfNulls<SettingRow>(1)
        val button = WoodButton(x, y, w, 20, Component.literal(label)) { cycle(holder[0]!!) }
        val row = SettingRow(field, dataIndex, label, values, button)
        holder[0] = row
        this.settingRows.add(row)
        this.addRenderableWidget(button)
        return y + step
    }

    private fun cycle(row: SettingRow) {
        val current = this.menu.getSetting(row.dataIndex)
        val next = (current + 1) % row.values.size
        if (this.minecraft != null && this.minecraft.player != null) {
            MaidNet.send(MaidSettingsPayload(row.field, next))
        }
    }

    private fun send(action: Int) {
        if (this.minecraft != null && this.minecraft.player != null) {
            MaidNet.send(MaidCommandPayload(action))
        }
    }

    override fun containerTick() {
        super.containerTick()
        for ((_, dataIndex, label, values, button) in this.settingRows) {
            var idx = this.menu.getSetting(dataIndex)
            if (idx < 0 || idx >= values.size) {
                idx = 0
            }
            button.message = Component.literal(label + "：" + values[idx])
        }
        val sw = this.stateWidget
        if (sw != null) {
            val state = (if (this.menu.getSitState() == 1) "坐下待命" else "跟随中") +
                "    生命 " + this.menu.getHealthInt()
            sw.message = Component.literal(state)
            this.centeredWidget(sw)
        }
    }

    override fun extractBackground(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        super.extractBackground(extractor, mouseX, mouseY, tickDelta)
        extractor.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, PANEL_TEX,
            this.leftPos, this.topPos, 0f, 0f, PANEL_W, PANEL_H, PANEL_W, PANEL_H)
    }

    override fun extractLabels(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        // 文字全部由 widget 承载；这里屏蔽原版 title 与「物品栏」标签
    }

    companion object {
        private const val PANEL_W = 268
        private const val PANEL_H = 280

        private val PANEL_TEX: Identifier =
            Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "textures/gui/maid_panel.png")
    }
}
