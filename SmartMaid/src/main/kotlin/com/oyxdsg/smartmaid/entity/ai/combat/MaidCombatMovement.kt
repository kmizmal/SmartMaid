package com.oyxdsg.smartmaid.entity.ai.combat

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.MaidMoveControl
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity

class MaidCombatMovement {
    private var side = 1.0f
    private var sideSwitchCooldown = 0
    private var stuckTicks = 0
    private var lastStateLogTick = -100

    fun keepDistance(maid: SmartMaidEntity, target: LivingEntity, wantedDistance: Double) =
        keepDistance(maid, target, wantedDistance, wantedDistance + DISTANCE_DEAD_ZONE)

    fun keepDistance(maid: SmartMaidEntity, target: LivingEntity, wantedDistance: Double, maxDistance: Double) {
        val dx = target.x - maid.x
        val dz = target.z - maid.z
        val dist = kotlin.math.max(1.0E-4, kotlin.math.sqrt(dx * dx + dz * dz))
        maid.yRot = Math.toDegrees(Mth.atan2(-dx, dz)).toFloat()
        val forward = when {
            dist < wantedDistance - DISTANCE_DEAD_ZONE -> -1.0f
            dist > maxDistance -> Mth.clamp(((dist - wantedDistance) * 0.5).toFloat(), 0.0f, 1.0f)
            else -> 0.0f
        }
        var strafe = 0.0f
        if (forward < 0.0f && maid.horizontalCollision) {
            if (tryBackJump(maid, dx, dz, dist)) stuckTicks = 0 else {
                stuckTicks++
                if (stuckTicks % 40 == 0) MaidDebug.log("Combat 后退受阻 ${stuckTicks}t（墙角？尝试绕行）")
                if (sideSwitchCooldown <= 0) {
                    side = pickOpenSide(maid)
                    sideSwitchCooldown = DETOUR_COOLDOWN
                    MaidDebug.log("Combat detour 绕行 side=$side (stuck=${stuckTicks}t)")
                }
                strafe = side
            }
        } else stuckTicks = 0
        if (sideSwitchCooldown > 0) sideSwitchCooldown--
        val move = maid.moveControl
        if (move is MaidMoveControl) move.combatStrafe(forward, strafe, STRAFE_SPEED) else move.strafe(forward, strafe)
        if (maid.tickCount - lastStateLogTick >= 20) {
            lastStateLogTick = maid.tickCount
            MaidDebug.log("Combat strafe dist=${String.format("%.1f", dist)} fwd=${String.format("%.2f", forward)} side=${String.format("%.1f", strafe)}${if (forward < 0.0f) " (后退/面向敌人)" else ""}")
        }
    }

    private fun tryBackJump(maid: SmartMaidEntity, dx: Double, dz: Double, dist: Double): Boolean {
        if (!maid.onGround() || !maid.horizontalCollision) return true
        val ox = kotlin.math.round(-dx / dist).toInt(); val oz = kotlin.math.round(-dz / dist).toInt()
        if (ox == 0 && oz == 0) return true
        val foot = maid.blockPosition().offset(ox, 0, oz); val head = foot.above()
        val shape = maid.level().getBlockState(foot).getCollisionShape(maid.level(), foot)
        if (shape.isEmpty || !maid.level().getBlockState(head).getCollisionShape(maid.level(), head).isEmpty) return true
        val topY = shape.max(Direction.Axis.Y) + foot.y
        if (topY <= maid.y + 1.0) { MaidDebug.log("Combat back-jump 背对跳 -> $foot"); maid.jumpControl.jump(); return true }
        return false
    }

    private fun pickOpenSide(maid: SmartMaidEntity): Float {
        val yaw = Math.toRadians(maid.yRot.toDouble()); val rx = kotlin.math.cos(yaw); val rz = kotlin.math.sin(yaw)
        val rightOpen = isPassable(maid, maid.blockPosition().offset(kotlin.math.round(rx).toInt(), 0, kotlin.math.round(rz).toInt()))
        val leftOpen = isPassable(maid, maid.blockPosition().offset(kotlin.math.round(-rx).toInt(), 0, kotlin.math.round(-rz).toInt()))
        if (rightOpen && !leftOpen) return 1.0f
        if (leftOpen && !rightOpen) return -1.0f
        return if (side >= 0.0f) -1.0f else 1.0f
    }

    private fun isPassable(maid: SmartMaidEntity, pos: BlockPos): Boolean {
        val foot = maid.level().getBlockState(pos); val head = maid.level().getBlockState(pos.above())
        return foot.getCollisionShape(maid.level(), pos).isEmpty && head.getCollisionShape(maid.level(), pos.above()).isEmpty
    }

    private companion object { const val STRAFE_SPEED = 1.0; const val DISTANCE_DEAD_ZONE = 0.3; const val DETOUR_COOLDOWN = 20 }
}
