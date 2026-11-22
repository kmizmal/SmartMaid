package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
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

    override fun canStart(maid: SmartMaidEntity): Boolean = target != null

    override fun start(maid: SmartMaidEntity) {
        MaidDebug.log("Move start -> $target")
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
        if (maid.navigation.isDone || maid.tickCount % 20 == 0) {
            MaidActions.navigateTo(maid, target, speed)
        }
    }

    override fun isDone(): Boolean = done

    override fun result(): String = if (done) "已到达" else "移动中"
}
