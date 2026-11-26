package com.oyxdsg.smartmaid.entity.ai

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 女仆跳跃动作执行器。跨格跳跃：先走到起跳边缘（POSITIONING），再按 MaidJumpTable 解
 * 起跳（JUMP），落地后短暂冷却（COOLDOWN）。Java 侧公开 API（构造器 / active() /
 * jumping() / tryHandle() / tick()）保持不变。
 */
open class MaidActionExecutor(private val maid: SmartMaidEntity) {

    private enum class State { IDLE, POSITIONING, JUMP, COOLDOWN }

    private var state: State = State.IDLE
    private var edgePos: Vec3 = Vec3.ZERO
    private var yawRad: Double = 0.0
    private var sprint: Boolean = false
    private var airTicks: Int = 0
    private var positioningTicks: Int = 0

    /** 是否正在执行跳跃动作（含定位/冷却阶段）。 */
    open fun active(): Boolean = this.state != State.IDLE

    /** 是否处于空中起跳阶段。 */
    open fun jumping(): Boolean = this.state == State.JUMP

    /**
     * 尝试接管 from -> to 的跨格跳跃。成功返回 true（调用方应让出移动控制）。
     */
    open fun tryHandle(from: BlockPos, to: BlockPos): Boolean {
        if (this.state != State.IDLE || from == to) return false
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val hDist = sqrt((dx * dx + dz * dz).toDouble())
        if (hDist !in MIN_H_DIST..MAX_H_DIST) return false
        if (dy > 1 || dy < -3) return false
        if (!isJumpable(from, to)) return false
        val dist = hDist.roundToInt()
        val sol = MaidJumpTable.get(dist, dy) ?: return false
        this.sprint = sol.sprint
        start(from, to, dx.toDouble() / hDist, dz.toDouble() / hDist, sol.back)
        return true
    }

    /** 每 tick 驱动：定位 / 起跳 / 冷却三段状态机。 */
    open fun tick() {
        if (this.maid.isOrderedToSit) return
        when (this.state) {
            State.POSITIONING -> tickPositioning()
            State.JUMP -> tickJump()
            State.COOLDOWN -> tickCooldown()
            State.IDLE -> {}
        }
    }

    private fun start(from: BlockPos, to: BlockPos, nx: Double, nz: Double, back: Double) {
        this.state = State.POSITIONING
        this.positioningTicks = 0
        val offset = (0.5 - EDGE_INSET) - back
        this.edgePos = Vec3(
            from.x.toDouble() + 0.5 + nx * offset,
            from.y.toDouble() + 0.5,
            from.z.toDouble() + 0.5 + nz * offset
        )
        val tx = to.x.toDouble() + 0.5 - this.edgePos.x
        val tz = to.z.toDouble() + 0.5 - this.edgePos.z
        this.yawRad = Mth.atan2(-tx, tz)
        this.maid.navigation.stop()
        // 直线降级可能仍持有目标并持续改写 MoveControl；起跳定位前必须一并清掉。
        (this.maid.navigation as? MaidGroundPathNavigation)?.straightNav?.clear(this.maid)
        val speed = if (this.sprint) 1.3 else 1.0
        this.maid.moveControl.setWantedPosition(this.edgePos.x, this.edgePos.y, this.edgePos.z, speed)
        this.maid.setSprinting(this.sprint)
        val takeoff = JumpPhysics.takeoffSpeed(
            this.maid.getAttributeValue(Attributes.MOVEMENT_SPEED), this.sprint
        )
        MaidDebug.log(
            "Jump start " + from + " -> " + to +
                " sprint=" + this.sprint +
                " back=" + MaidDebug.fmt1(back) +
                " takeoff=" + MaidDebug.fmt1(takeoff) +
                " ticks=" + JumpPhysics.flightTicks()
        )
    }

    private fun tickPositioning() {
        this.positioningTicks++
        val pos = this.maid.position()
        val d = sqrt((pos.x - this.edgePos.x) * (pos.x - this.edgePos.x) +
            (pos.z - this.edgePos.z) * (pos.z - this.edgePos.z))
        val speed = if (this.sprint) 1.3 else 1.0
        this.maid.moveControl.setWantedPosition(this.edgePos.x, this.edgePos.y, this.edgePos.z, speed)
        if ((d <= EDGE_REACH && this.maid.onGround()) || this.positioningTicks > MAX_POSITIONING_TICKS) {
            doJump()
        }
    }

    private fun doJump() {
        this.state = State.JUMP
        this.airTicks = 0
        this.maid.yRot = Math.toDegrees(this.yawRad).toFloat()
        this.maid.jumpControl.jump()
        val takeoff = JumpPhysics.takeoffSpeed(
            this.maid.getAttributeValue(Attributes.MOVEMENT_SPEED), this.sprint
        )
        val vel = this.maid.deltaMovement
        this.maid.setDeltaMovement(-sin(this.yawRad) * takeoff, vel.y, cos(this.yawRad) * takeoff)
        this.maid.navigation.stop()
        MaidDebug.log(
            "Jump fired yaw=" + Math.toDegrees(this.yawRad).toInt() +
                " sprint=" + this.sprint +
                " takeoff=" + MaidDebug.fmt1(takeoff) +
                " vel=" + this.maid.deltaMovement
        )
    }

    private fun tickJump() {
        this.airTicks++
        // 1.0 才是原版“持续前进”输入；2.0 会让空中水平速度超出解表假设。
        this.maid.setZza(1.0f)
        this.maid.setXxa(0.0f)
        if (this.maid.onGround() || this.airTicks >= MAX_AIR_TICKS) {
            this.state = State.COOLDOWN
            val suffix = if (this.maid.onGround()) "" else " (airborne timeout)"
            MaidDebug.log("Jump landed at " + this.maid.blockPosition() + suffix)
        }
    }

    private fun tickCooldown() {
        this.maid.setSprinting(false)
        this.state = State.IDLE
    }

    /** 起跳路径检测：中间行至少有一格空隙/障碍（需跨越），且终点有落脚方块。 */
    private fun isJumpable(from: BlockPos, to: BlockPos): Boolean {
        val sx = Integer.signum(to.x - from.x)
        val sz = Integer.signum(to.z - from.z)
        val dx = to.x - from.x
        val dz = to.z - from.z
        val steps = max(abs(dx), abs(dz))
        var hasGapOrObstacle = false
        var cursor: BlockPos = from
        for (i in 1 until steps) {
            cursor = cursor.offset(sx, 0, sz)
            val body: BlockState = this.maid.level().getBlockState(cursor)
            val below: BlockState = this.maid.level().getBlockState(cursor.below())
            if (body.isAir && below.isAir) {
                hasGapOrObstacle = true
            } else if (body.isAir) {
                return false
            } else {
                val above: BlockState = this.maid.level().getBlockState(cursor.above())
                if (!above.isAir) return false
                hasGapOrObstacle = true
            }
        }
        if (!hasGapOrObstacle) return false
        return !this.maid.level().getBlockState(to.below()).isAir
    }

    private companion object {
        private const val EDGE_INSET = 0.1
        private const val EDGE_REACH = 0.25
        private const val MIN_H_DIST = 2.0
        private const val MAX_H_DIST = 4.0
        private const val MAX_AIR_TICKS = 30
        private const val MAX_POSITIONING_TICKS = 60
    }
}
