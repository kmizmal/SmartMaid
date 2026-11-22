package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item

/**
 * 进食任务：吃背包里的食物（itemFilter 为空则吃营养最高、其次饱和度最高的食物）。
 *
 * <p>换到主手后走原版 startUsingItem(MAINHAND)，由 LivingEntity.tick
 * 自动推进并在完成时调用 completeUsingItem：食物效果由 Consumable 应用，
 * 饱食度由 SmartMaidEntity.completeUsingItem() 补记（女仆非玩家，原版只给玩家加饱食）。</p>
 */
class EatTask(private val itemFilter: Item?) : MaidAITask("eat") {

    private var failed = false
    private var finished = false
    private var ticks = 0

    companion object {
        /** 兜底超时（防止使用状态异常卡住任务） */
        private const val MAX_TICKS = 20 * 10
    }

    override fun canStart(maid: SmartMaidEntity): Boolean = true

    override fun start(maid: SmartMaidEntity) {
        val slot = MaidActions.findFoodSlot(maid, itemFilter)
        if (slot < 0) {
            this.failed = true
            maid.showBubble(
                Component.literal(if (itemFilter == null) "没有可吃的食物" else "背包里没有这个食物"),
                60
            )
            return
        }
        if (!MaidActions.swapToMainhand(maid, slot) || !MaidActions.startEating(maid)) {
            this.failed = true
            maid.showBubble(Component.literal("这个吃不了"), 60)
            return
        }
        MaidDebug.log("Eat start slot=" + slot + " item=" + MaidDebug.itemName(maid.getMainHandItem()))
    }

    override fun tick(maid: SmartMaidEntity) {
        this.ticks++
        if (!this.failed && !maid.isUsingItem()) {
            this.finished = true
        }
    }

    override fun isDone(): Boolean = this.failed || this.finished || this.ticks > MAX_TICKS

    override fun result(): String = if (this.failed) "没有食物" else "已进食"

    override fun forceStop(maid: SmartMaidEntity?) {
        if (maid != null && maid.isUsingItem()) {
            maid.stopUsingItem()
        }
    }
}
