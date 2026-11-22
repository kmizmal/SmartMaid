package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.oyxdsg.smartmaid.compat.MaidCompat
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionBlockUtil
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * 打开容器任务（存箱子第一步）：在提示位置附近定位实际容器 → 走到面前 →
 * 触发真实开箱动画（ChestBlockEntity.startOpen，女仆实现 ContainerUser）→ 女仆动作 →
 * 记录"当前打开的箱子"，供后续 chestput / chesttake 使用。
 */
class ChestOpenTask(private val hint: BlockPos?) : MaidAITask("chestopen") {

    private var target: BlockPos? = null
    private var done = false

    companion object {
        /** 在提示位置周围搜索容器的半径 */
        private const val SEARCH_RADIUS = 4
    }

    override fun canStart(maid: SmartMaidEntity): Boolean = this.hint != null

    override fun start(maid: SmartMaidEntity) {
        this.done = false
        // 智能定位：先看提示位置本身，再在周围搜索最近的容器
        this.target = findContainerNear(maid, this.hint)
        val t = this.target
        if (t == null) {
            maid.showBubble(Component.literal("附近没有箱子"), 60)
            MaidDebug.log("ChestOpen 失败: " + hint + " 附近无容器")
            this.done = true
            return
        }
        MaidDebug.log("ChestOpen start hint=" + hint + " -> 实际容器=" + t)
    }

    override fun tick(maid: SmartMaidEntity) {
        val t = this.target
        if (this.done || t == null) {
            return
        }
        if (!MaidActions.isWithinReach(maid, t, 2.5)) {
            if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
                MaidActions.navigateTo(maid, t, 1.0)
            }
            return
        }
        maid.getNavigation().stop()
        // 面朝箱子 + 女仆开箱动作
        maid.getLookControl().setLookAt(t.x + 0.5, t.y + 0.5, t.z + 0.5)
        MaidCompat.swing(maid, InteractionHand.MAIN_HAND)
        // 触发真实开箱动画（openCount 机制，客户端箱子盖打开）
        val be = maid.level().getBlockEntity(t)
        if (be is ChestBlockEntity) {
            be.startOpen(maid)
            MaidDebug.log("ChestOpen 开箱动画: " + t)
        } else {
            // 非箱子容器（如熔炉）无开盖动画，仅记录
            MaidDebug.log("ChestOpen 容器(非箱子): " + t)
        }
        // 记录为"当前打开的箱子"
        maid.setOpenedContainer(t)
        this.done = true
    }

    override fun isDone(): Boolean = this.done

    override fun result(): String = if (this.done) "已打开箱子 " + this.target else "打开中"

    /** 在提示位置及其周围搜索最近的容器方块（箱子/熔炉/漏斗等 Container）。实现已收拢到感知工具。 */
    private fun findContainerNear(maid: SmartMaidEntity, hint: BlockPos?): BlockPos? {
        if (hint != null && PerceptionBlockUtil.isContainerAt(maid.level(), hint)) {
            return hint
        }
        val center = hint ?: maid.blockPosition()
        return PerceptionBlockUtil.findNearestContainer(maid.level(), center, SEARCH_RADIUS)
    }
}
