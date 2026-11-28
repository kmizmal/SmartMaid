package com.oyxdsg.smartmaid.entity.ai

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
    private var takeoffCell: BlockPos = BlockPos.ZERO
    private var landingCell: BlockPos = BlockPos.ZERO
    private var yawRad: Double = 0.0
    private var sprint: Boolean = false
    private var airTicks: Int = 0
    private var positioningTicks: Int = 0

    /** 是否正在执行跳跃动作（含定位/冷却阶段）。 */
    open fun active(): Boolean = this.state != State.IDLE

    /** 是否处于空中起跳阶段。 */
    open fun jumping(): Boolean = this.state == State.JUMP

    /** 测试/传送重置：停止跳跃状态机并清掉直线降级目标。 */
    open fun reset() {
        this.state = State.IDLE
        this.airTicks = 0
        this.positioningTicks = 0
        this.maid.setSprinting(false)
        this.maid.navigation.stop()
        (this.maid.navigation as? MaidGroundPathNavigation)?.straightNav?.clear(this.maid)
    }

    /**
     * 尝试接管 from -> to 的跨格跳跃。成功返回 true（调用方应让出移动控制）。
     */
    open fun tryHandle(from: BlockPos, to: BlockPos): Boolean {
        if (this.state != State.IDLE || !maid.onGround()) return false
        val dx = to.x.toDouble() - from.x
        val dy = to.y.toLong() - from.y
        val dz = to.z.toDouble() - from.z
        val hDist = sqrt(dx * dx + dz * dz)
        if (hDist !in MIN_H_DIST..MAX_H_DIST || dy !in -3L..1L) return false
        val sol = MaidJumpTable.get(hDist.roundToInt(), dy.toInt()) ?: return false
        val edge = calibratedJumpLaunch(from, to, sol.back)
        if (!hasClearance(from, to, edge) || !isJumpable(from, to)) return false
        this.sprint = sol.sprint
        start(from, to, edge, sol.back)
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

    private fun start(from: BlockPos, to: BlockPos, edge: Vec3, back: Double) {
        this.state = State.POSITIONING
        this.positioningTicks = 0
        this.takeoffCell = from.immutable()
        this.landingCell = to.immutable()
        this.edgePos = edge
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
        val launchY = edgePos.y - 0.5
        val atLaunch = d <= EDGE_REACH && abs(pos.y - launchY) <= EDGE_REACH && maid.onGround()
        when (jumpPositioning(atLaunch, positioningTicks > MAX_POSITIONING_TICKS) {
            hasClearance(takeoffCell, landingCell, edgePos)
        }) {
            JumpPositioning.WAIT -> {
                val speed = if (sprint) 1.3 else 1.0
                maid.moveControl.setWantedPosition(edgePos.x, edgePos.y, edgePos.z, speed)
            }
            JumpPositioning.ABORT -> {
                maid.setSprinting(false)
                maid.moveControl.setWait()
                state = State.IDLE
                MaidDebug.log("Jump cancelled before takeoff: $takeoffCell -> $landingCell")
            }
            JumpPositioning.FIRE -> doJump()
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

    private fun isLoaded(pos: BlockPos): Boolean = maid.level().hasChunk(pos.x shr 4, pos.z shr 4)

    private fun hasClearance(from: BlockPos, to: BlockPos, edge: Vec3): Boolean {
        val level = maid.level()
        val launch = BlockPos.containing(edge.x, from.y.toDouble(), edge.z)
        if (minOf(launch.y, to.y) <= level.minY || !hasJumpClearance(launch, to, ::isLoaded,
                { level.getBlockState(it).isFaceSturdy(level, it, Direction.UP) },
                { level.getBlockState(it).getCollisionShape(level, it).isEmpty },
            )
        ) return false

        // Check the maid's actual width at the launch edge and the short approach to it.
        val dx = edge.x - maid.x
        val dz = edge.z - maid.z
        val approach = maid.boundingBox.expandTowards(dx, 0.0, dz)
        val launchBox = maid.boundingBox.move(dx, from.y - maid.y, dz).expandTowards(0.0, 0.42, 0.0)
        val envelope = approach.minmax(launchBox)
        for (cx in (Mth.floor(envelope.minX) shr 4)..(Mth.floor(envelope.maxX) shr 4)) {
            for (cz in (Mth.floor(envelope.minZ) shr 4)..(Mth.floor(envelope.maxZ) shr 4)) {
                if (!level.hasChunk(cx, cz)) return false
            }
        }
        return level.getBlockCollisions(maid, approach).none() && level.getBlockCollisions(maid, launchBox).none()
    }

    /** 起跳路径检测：中间行至少有一格空隙/障碍（需跨越）。 */
    private fun isJumpable(from: BlockPos, to: BlockPos): Boolean {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val steps = max(abs(dx), abs(dz))
        var hasGapOrObstacle = false
        for (i in 1 until steps) {
            val fraction = i.toDouble() / steps
            val cursor = BlockPos.containing(from.x + 0.5 + dx * fraction, from.y.toDouble(), from.z + 0.5 + dz * fraction)
            if (!isLoaded(cursor)) return false
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
        return hasGapOrObstacle
    }

    private companion object {
        private const val EDGE_REACH = 0.25
        private const val MIN_H_DIST = 2.0
        private const val MAX_H_DIST = 4.0
        private const val MAX_AIR_TICKS = 30
        private const val MAX_POSITIONING_TICKS = 60
    }
}
