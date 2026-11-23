package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * 喂食任务（M3）：走到玩家面前 → 面朝玩家 → 找背包食物 → 把食物"丢出来"给玩家。
 * 食物已丢出即完成。
 */
class FeedTask : MaidAITask("feed") {

    private var done = false

    companion object {
        private const val FACE_DISTANCE = 2.0
    }

    override fun canStart(maid: SmartMaidEntity): Boolean = maid.owner is Player

    override fun start(maid: SmartMaidEntity) {
        this.done = false
        MaidDebug.log("Feed start")
    }

    override fun tick(maid: SmartMaidEntity) {
        val owner = maid.owner as? Player
        if (owner == null || !owner.isAlive) {
            this.done = true
            return
        }
        // 1. 走到玩家面前
        val distSqr = maid.distanceToSqr(owner)
        if (distSqr > FACE_DISTANCE * FACE_DISTANCE) {
            if (maid.getNavigation().isDone || maid.tickCount % 20 == 0) {
                maid.getNavigation().moveTo(owner, 1.0)
            }
            return
        }
        maid.getNavigation().stop()
        // 2. 面朝玩家
        maid.getLookControl().setLookAt(owner, 10.0f, maid.maxHeadXRot.toFloat())
        // 3. 找背包食物 → 换到主手
        val food = findFood(maid)
        if (food == null) {
            maid.showBubble(Component.literal("背包里没有食物了"), 60)
            this.done = true
            return
        }
        // 4. 丢出来给玩家
        if (MaidActions.isFood(maid.mainHandItem)) {
            val drop = maid.mainHandItem.split(1)
            MaidActions.throwItem(maid, drop, owner, 0.5)
            MaidDebug.log("Feed 丢出食物: " + drop.item.getDescriptionId())
            this.done = true
        }
    }

    override fun isDone(): Boolean = this.done

    override fun result(): String {
        return if (this.done) "已喂食" else "喂食中断"
    }

    private fun findFood(maid: SmartMaidEntity): ItemStack? {
        if (MaidActions.isFood(maid.mainHandItem)) {
            return maid.mainHandItem
        }
        val slot = MaidActions.findInBackpack(maid) { stack -> MaidActions.isFood(stack) }
        if (slot < 0) {
            return null
        }
        // 换到主手（热键0）
        if (!MaidActions.equipFromBackpack(maid) { stack -> MaidActions.isFood(stack) }) {
            return null
        }
        return maid.mainHandItem
    }
}
