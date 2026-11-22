package com.oyxdsg.smartmaid.entity.ai

import com.oyxdsg.smartmaid.data.MaidSettings
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.goal.Goal
import java.util.EnumSet

class MaidFollowGoal(private val maid: SmartMaidEntity, private val speedModifier: Double) : Goal() {
    private var owner: LivingEntity? = null
    private var ticksToRecomputePath = 0
    init { flags = EnumSet.of(Flag.MOVE, Flag.LOOK) }
    private fun startDistance() = MaidSettings.FOLLOW_START_STEPS[maid.settings.followStartIndex]
    private fun stopDistance() = MaidSettings.FOLLOW_STOP_STEPS[maid.settings.followStopIndex]
    override fun canUse(): Boolean { if(maid.isAiBusy || !maid.settings.followEnabled) return false; val o=maid.owner ?: return false; if(o.isSpectator||maid.isOrderedToSit) return false; if(maid.distanceToSqr(o)<startDistance()*startDistance()) return false; owner=o; return true }
    override fun canContinueToUse(): Boolean { val o=owner ?: return false; if(maid.isOrderedToSit||!maid.settings.followEnabled) return false; return maid.distanceToSqr(o)>stopDistance()*stopDistance() }
    override fun start(){ ticksToRecomputePath=0 }
    override fun stop(){ owner=null; maid.navigation.stop() }
    override fun tick(){ val o=owner ?: return; if(maid.isOrderedToSit) return; maid.lookControl.setLookAt(o,10f,maid.maxHeadXRot.toFloat()); if(--ticksToRecomputePath<=0){ticksToRecomputePath=10; maid.navigation.moveTo(o,speedModifier)} }
}
