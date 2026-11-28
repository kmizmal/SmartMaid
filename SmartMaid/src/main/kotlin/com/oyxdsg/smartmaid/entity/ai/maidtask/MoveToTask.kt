package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.MaidGroundPathNavigation
import net.minecraft.core.BlockPos

/**
 * 移动到目标任务（基础指令 move_to）：持续寻路直到到达目标格（含跳跃自动生效）。
 */
class MoveToTask(
    private val target: BlockPos,
    private val speed: Double,
    private val arriveRange: Double
) : MaidAITask("move") {

    private var done = false
    private var stoppedForGap = false

    override fun canStart(maid: SmartMaidEntity): Boolean = true

    override fun start(maid: SmartMaidEntity) {
        MaidDebug.log("Move start -> $target")
        (maid.navigation as? MaidGroundPathNavigation)?.clearGapBlock()
        done = MaidActions.isWithinReach(maid, target, arriveRange)
    }

    override fun tick(maid: SmartMaidEntity) {
        if (done) return
        if (MaidActions.isWithinReach(maid, target, arriveRange)) {
            maid.navigation.stop()
            done = true
            MaidDebug.log("Move 到达 $target")
            return
        }
        if ((maid.navigation as? MaidGroundPathNavigation)?.isBlockedByWideGap(target) == true) {
            maid.navigation.stop()
            done = true
            stoppedForGap = true
            MaidDebug.log("Move 停止：前方四格以上深沟 $target")
            return
        }
        if (maid.navigation.isDone || maid.tickCount % 20 == 0) {
            MaidActions.navigateTo(maid, target, speed)
        }
    }

    override fun forceStop(maid: SmartMaidEntity?) {
        val nav = maid?.navigation ?: return
        if (nav is MaidGroundPathNavigation) {
            nav.stopWithFallback()
            nav.clearGapBlock()
        } else nav.stop()
    }

    override fun isDone(): Boolean = done

    override fun result(): String = when {
        stoppedForGap -> "已停止（前方四格以上深沟）"
        done -> "已到达"
        else -> "移动中"
    }
}
