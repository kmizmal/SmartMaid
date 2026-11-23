package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import net.minecraft.world.entity.LivingEntity

/**
 * 护卫任务（M3，持续型）：主动索敌并攻击周围敌人，无目标时原地待命。
 * 不因超时结束，靠 cancel / 坐下终止。
 */
class GuardTask(private var range: Int, private val defensive: Boolean) : MaidAITask("guard") {

    private var target: LivingEntity? = null
    private var cooldown = 0

    companion object {
        private const val ATTACK_COOLDOWN = 20
    }

    override fun canStart(maid: SmartMaidEntity): Boolean = true

    override fun isContinuous(): Boolean = true

    /** guard 合并（§8.8a）：不新增任务，把现有护卫范围更新为两者较大值。 */
    fun setRange(range: Int) {
        this.range = range
    }

    override fun start(maid: SmartMaidEntity) {
        this.target = null
        this.cooldown = 0
        MaidDebug.log("Guard start range=" + range + " defensive=" + defensive)
    }

    override fun tick(maid: SmartMaidEntity) {
        val t = this.target
        if (t == null || !t.isAlive) {
            // 找下一个目标（defensive 只反击打主人的敌人）
            this.target = findTarget(maid)
            return
        }
        maid.getLookControl().setLookAt(t, 10.0f, maid.maxHeadXRot.toFloat())
        val distSqr = maid.distanceToSqr(t)
        if (distSqr > 2.5 * 2.5) {
            if (maid.getNavigation().isDone || maid.tickCount % 20 == 0) {
                maid.getNavigation().moveTo(t, 1.0)
            }
        } else {
            maid.getNavigation().stop()
            if (--cooldown <= 0) {
                MaidActions.attack(maid, t)
                cooldown = ATTACK_COOLDOWN
            }
        }
    }

    override fun isDone(): Boolean = false // 持续型，靠 cancel/坐下终止

    override fun result(): String = "护卫中"

    private fun findTarget(maid: SmartMaidEntity): LivingEntity? {
        val last = maid.lastHurtByMob
        if (defensive && last != null && last.isAlive) {
            return last
        }
        return AttackTask.findNearestHostile(maid, range)
    }
}
