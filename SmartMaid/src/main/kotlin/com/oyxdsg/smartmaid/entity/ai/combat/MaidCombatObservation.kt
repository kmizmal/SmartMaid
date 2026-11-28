package com.oyxdsg.smartmaid.entity.ai.combat

/** 只读战斗观测快照；供测试记录，不参与决策。 */
data class MaidCombatObservation(
    val engaged: Boolean,
    val mode: String,
    val actionCooldown: Int,
    val targetId: Int,
    val maidTargetId: Int,
    val lostTicks: Int,
    val eatRetreatTicks: Int,
    val blockStartTick: Int,
    val blockHoldTicks: Int,
    val tickCalls: Long,
    val lastTickGameTime: Int
)
