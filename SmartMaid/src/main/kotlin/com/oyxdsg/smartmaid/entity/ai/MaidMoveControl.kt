package com.oyxdsg.smartmaid.entity.ai

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.MoveControl

class MaidMoveControl(private val maid: SmartMaidEntity) : MoveControl<SmartMaidEntity>(maid) {
    override fun tick(){
        if(maid.actionExecutor.jumping()) return; if(operation==Operation.MOVE_TO){ operation=Operation.WAIT; val dx=wantedX-mob.x; val dy=wantedY-mob.y; val dz=wantedZ-mob.z; if(dx*dx+dy*dy+dz*dz<2.5E-7){mob.zza=0f; return}; val from=mob.blockPosition(); val to=BlockPos(Mth.floor(wantedX),Mth.floor(wantedY),Mth.floor(wantedZ)); if(!straightNavActive()&&maid.actionExecutor.tryHandle(from,to))return; val angle=Math.toDegrees(Mth.atan2(dz,dx)).toFloat()-90f; mob.yRot=rotlerp(mob.yRot,angle,90f); mob.speed=(speedModifier*mob.getAttributeValue(Attributes.MOVEMENT_SPEED)).toFloat(); mob.zza=1f; mob.xxa=0f; val bp=mob.blockPosition(); val bs=mob.level().getBlockState(bp); val shape=bs.getCollisionShape(mob.level(),bp); val need=(mob.maxUpStep()<dy&&dx*dx+dz*dz<kotlin.math.max(1f,mob.bbWidth))||(!shape.isEmpty&&mob.y<shape.max(Direction.Axis.Y)+bp.y&&!bs.`is`(BlockTags.DOORS)&&!bs.`is`(BlockTags.FENCES)&&!bs.`is`(BlockTags.CLIMBABLE)); if(need){mob.jumpControl.jump(); operation=Operation.JUMPING} } else if(operation==Operation.JUMPING&&mob.isInWater) operation=Operation.WAIT else super.tick() }
    private fun straightNavActive() = (maid.navigation as? MaidGroundPathNavigation)?.straightNav?.isActive == true
    fun combatStrafe(forward:Float,right:Float,speedModifier:Double){ super.strafe(forward,right); this.speedModifier=speedModifier }
}
