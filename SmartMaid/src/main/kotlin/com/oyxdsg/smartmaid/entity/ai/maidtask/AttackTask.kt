package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionBlockUtil
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity

/**
 * 攻击任务（M2）：锁定目标 → 追击 → 近战，目标死亡/丢失即完成。
 *
 * <p>目标模式（构造参数指定，优先级从高到低）：</p>
 * <ul>
 *     <li>指定实体（explicitTarget）：AI 显式给出目标实体</li>
 *     <li>指定类型（targetType）：打周围最近的该种生物（如猪/牛/羊，用于获取食物）</li>
 *     <li>自动：锁定周围最近敌对生物（Monster）</li>
 * </ul>
 */
class AttackTask(
    private val range: Int,
    private val explicitTarget: LivingEntity?,
    private val targetType: EntityType<*>?,
) : MaidAITask("attack") {

    constructor(range: Int, explicitTarget: LivingEntity?) : this(range, explicitTarget, null)
    constructor(range: Int, targetType: EntityType<*>?) : this(range, null, targetType)
    constructor(range: Int) : this(range, null, null)

    private var target: LivingEntity? = null
    private var cooldown = 0

    companion object {
        /** 攻击冷却 tick */
        private const val ATTACK_COOLDOWN = 20

        /** 找周围最近敌对生物（Monster，排除己方/玩家）。实现已收拢到感知工具 PerceptionBlockUtil。 */
        @JvmStatic
        fun findNearestHostile(maid: SmartMaidEntity, range: Int): LivingEntity? =
            PerceptionBlockUtil.findNearestHostile(maid, range)
    }

    override fun canStart(maid: SmartMaidEntity): Boolean = true

    override fun start(maid: SmartMaidEntity) {
        this.target = when {
            explicitTarget != null && explicitTarget.isAlive -> explicitTarget
            targetType != null -> PerceptionBlockUtil.findNearestByType(maid, range, targetType)
            else -> findNearestHostile(maid, range)
        }
        this.cooldown = 0
        val t = this.target
        MaidDebug.log("Attack start target=" +
                (t?.let { it.type.toShortString() + " @" + it.blockPosition() } ?: "无") +
                (if (targetType != null) " wantType=" + targetType.toShortString() else ""))
        if (t == null) {
            maid.showBubble(Component.literal("附近没有" + targetLabel()), 60)
        }
    }

    override fun tick(maid: SmartMaidEntity) {
        val t = this.target
        if (t == null || !t.isAlive) {
            return // isDone 捕获，任务结束
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

    override fun isDone(): Boolean {
        val t = this.target
        return t == null || !t.isAlive
    }

    override fun result(): String {
        val t = this.target ?: return if (targetType != null) "附近没有" + targetLabel() else "无目标"
        return if (!t.isAlive) "已击杀 " + t.type.toShortString() else "目标丢失"
    }

    /** 目标类型的中文显示名；未指定类型时返回「敌人」。 */
    private fun targetLabel(): String = targetType?.getDescription()?.string ?: "敌人"
}
