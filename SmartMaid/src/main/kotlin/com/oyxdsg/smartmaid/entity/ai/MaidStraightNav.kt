package com.oyxdsg.smartmaid.entity.ai

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * 女仆"物理直线路径"降级导航（寻路兜底）。
 *
 * 原版寻路失败 / 没真正到目标 / 绕远 → 激活，改用直线朝目标移动；遇到障碍按需挖墙、
 * 挖上方、跳台阶、搭方块垫脚、pillar 垫高。到达/无进展/不可挖时结束并恢复原版寻路（带冷却）。
 */
class MaidStraightNav {
    companion object {
        private const val IDLE_LIMIT = 200
        private const val SPEED = 1.0
        /** 放弃后冷却 tick，期间不重新降级 */
        private const val GIVE_UP_COOLDOWN = 60
        /** 起跳后第 3 tick 脚底离地、原格空出 → 往脚下放方块 */
        private const val PILLAR_PLACE_TICK = 3
        /** 一次起跳到落地的完整循环上限 */
        private const val PILLAR_TIMEOUT = 60
    }

    private val breaker = MaidBlockBreaker()
    private val placer = MaidBlockPlacer()

    private var target: BlockPos? = null
    private var idleTicks = 0
    private var lastPos: BlockPos? = null
    private var giveUpTick = -1000

    // pillar（垂直爬高）状态
    private var pillarJumped = false
    private var pillarCell: BlockPos? = null
    private var pillarTicks = 0

    /** Java 侧 isActive() / Kotlin 侧 .isActive 均可用（Kotlin val 生成 isActive()）。 */
    val isActive: Boolean
        get() = target != null

    /** 当前直线目标（可能 null）。保留 Java 的 target() 方法形态。 */
    fun target(): BlockPos? = target

    /** 激活直线降级，目标 pos（null 直接忽略，行为与原版一致）。 */
    fun setTarget(pos: BlockPos?) {
        if (pos == null) return
        this.target = pos.immutable()
        this.idleTicks = 0
        this.lastPos = null
        this.breaker.setMoveStraight(true)
        this.placer.setMoveStraight(true)
        // 搭路只消耗普通建材，不浪费贵重物品
        this.placer.setBridgeMode(true)
    }

    /** 清空降级状态并清理进行中动作的裂纹。 */
    fun clear(maid: SmartMaidEntity?) {
        this.target = null
        this.idleTicks = 0
        this.lastPos = null
        this.pillarJumped = false
        this.pillarCell = null
        if (maid != null) this.breaker.abort(maid)
        this.placer.abort()
    }

    /** 最近是否刚放弃过（触发侧冷却，防止立刻重新降级）。 */
    fun recentlyGaveUp(currentTick: Int): Boolean = currentTick - this.giveUpTick < GIVE_UP_COOLDOWN

    /**
     * 每服务端 tick 驱动直线移动 + 破坏/搭路。
     * @return true = 已到达或放弃（调用方应 [clear] 恢复原版寻路）
     */
    fun tick(maid: SmartMaidEntity): Boolean {
        val t = this.target ?: return true
        if (maid.level().isClientSide) return false
        // 坐下 = 原地不动
        if (maid.isOrderedToSit) {
            this.breaker.abort(maid)
            this.placer.abort()
            return true
        }
        // 到达（水平 + 垂直 + 视线）
        if (isArrived(maid, t)) {
            MaidDebug.log("StraightNav 到达 $t")
            this.breaker.abort(maid)
            return true
        }
        // 优先完成当前挖掘/搭方块
        if (this.breaker.isActive()) {
            this.breaker.tick(maid)
            return false
        }
        if (this.placer.isActive()) {
            this.placer.tick(maid)
            return false
        }

        // 无进展检测
        this.idleTicks++
        val cp = maid.blockPosition()
        val lp = this.lastPos
        if (lp != null && (cp.distSqr(lp) > 4.0 || cp.y > lp.y)) {
            this.idleTicks = 0
        }
        this.lastPos = cp
        if (this.idleTicks > IDLE_LIMIT) return fail(maid, "无进展")

        val from = maid.blockPosition()
        val dx = Integer.signum(t.x - from.x)
        val dz = Integer.signum(t.z - from.z)
        val level = maid.level()

        // 垂直同柱（目标正上/正下，罕见）
        if (dx == 0 && dz == 0) {
            if (t.y > from.y) {
                val head = from.above()
                if (isSolid(level, head)) {
                    if (this.breaker.begin(maid, head)) {
                        MaidDebug.log("StraightNav 挖头顶 $head")
                        return false
                    }
                    return fail(maid, "头顶不可挖")
                }
                // 头顶净空：跳不上（高度差 >1）→ pillar 垫高爬升
                val dyUp = t.y - from.y
                if (dyUp > 1) return tickPillar(maid, from)
                // 高度差 1 → 跳一下
                maid.getJumpControl().jump()
            }
            moveTo(maid, t.x.toDouble(), from.y.toDouble(), t.z.toDouble())
            return false
        }

        // 水平推进：检查前方 1 格
        val ahead = from.offset(dx, 0, dz)
        val below = ahead.below()
        val aheadSolid = isSolid(level, ahead)
        val headSolid = isSolid(level, ahead.above())
        val belowSolid = isSolid(level, below)
        val below2Solid = isSolid(level, below.below())
        val needUp = t.y - from.y > 1

        // 1) 前方 2 格以上墙
        if (aheadSolid && headSolid) {
            val dig = if (needUp) ahead.above() else ahead
            if (this.breaker.begin(maid, dig)) {
                MaidDebug.log("StraightNav " + (if (needUp) "挖上方爬升 " else "挖 ") + dig + " (目标 $t)")
                return false
            }
            return fail(maid, "墙不可挖")
        }
        // 2) 前方头顶被挡 → 挖上方
        if (headSolid) {
            if (this.breaker.begin(maid, ahead.above())) {
                MaidDebug.log("StraightNav 挖上方 ${ahead.above()} (目标 $t)")
                return false
            }
            return fail(maid, "上方不可挖")
        }
        // 3) 前方 1 格高台阶/沟壁 → 跳上去
        if (aheadSolid) {
            maid.getJumpControl().jump()
            moveTo(maid, ahead.x.toDouble(), from.y.toDouble(), ahead.z.toDouble())
            return false
        }
        // 4) 前方脚下是深沟/水面 → 搭方块垫脚
        if (!belowSolid && !below2Solid) {
            if (this.placer.begin(maid, below)) {
                MaidDebug.log("StraightNav 搭 $below (目标 $t)")
                return false
            }
            return fail(maid, "搭路无方块")
        }
        // 5) 小落差/平地/坡 → 直线走
        moveTo(maid, t.x.toDouble(), from.y.toDouble(), t.z.toDouble())
        return false
    }

    /** MoveControl 直线走到 (x, y, z)（wantedY 保持当前高度，y 只做参考）。 */
    private fun moveTo(maid: SmartMaidEntity, x: Double, y: Double, z: Double) {
        maid.getMoveControl().setWantedPosition(x + 0.5, y, z + 0.5, SPEED)
    }

    /** Pillar 垫高爬升：目标在正上方且高度差 >1（跳不上）。 */
    private fun tickPillar(maid: SmartMaidEntity, from: BlockPos): Boolean {
        // 1) 未起跳 → 备块 + 起跳
        if (!this.pillarJumped) {
            if (!MaidActions.isBridgeBlock(maid.mainHandItem)) {
                if (!MaidActions.equipBridgeBlockFromBackpack(maid)) {
                    return fail(maid, "pillar 无方块")
                }
            }
            maid.getNavigation().stop()
            maid.getMoveControl().setWait()
            this.pillarJumped = true
            this.pillarCell = from.immutable()
            this.pillarTicks = 0
            maid.getJumpControl().jump()
            MaidDebug.log("StraightNav pillar 起跳 @${this.pillarCell}")
            return true
        }

        this.pillarTicks++
        // 2) 起跳后第 PILLAR_PLACE_TICK tick → 放方块到脚下
        if (this.pillarTicks == PILLAR_PLACE_TICK) {
            val cell = this.pillarCell
            if (cell != null && MaidActions.placeBlock(maid, cell.below(), Direction.UP)) {
                MaidDebug.log("StraightNav pillar 垫 @$cell (y=${cell.y})")
            }
        }
        // 3) 落地：站在垫高方块上 → 本层完成
        val cell = this.pillarCell
        if (cell != null && maid.onGround() && isSolid(maid.level(), cell)) {
            MaidDebug.log("StraightNav pillar 上一层 -> y=${maid.blockPosition().y}")
            this.pillarJumped = false
            this.pillarCell = null
            return true
        }
        // 超时保护
        if (this.pillarTicks > PILLAR_TIMEOUT) {
            this.pillarJumped = false
            this.pillarCell = null
            return fail(maid, "pillar 超时")
        }
        return true
    }

    /** 结束降级并记录放弃时刻（触发侧冷却），清裂纹。 */
    private fun fail(maid: SmartMaidEntity, reason: String): Boolean {
        this.giveUpTick = maid.tickCount
        MaidDebug.log("StraightNav 放弃($reason) $target")
        this.breaker.abort(maid)
        return true
    }

    /** 是否已到达目标（水平 2 格内 + 垂直 1.5 格内 + 视线不被墙隔开）。 */
    private fun isArrived(maid: SmartMaidEntity, target: BlockPos): Boolean {
        val dx = maid.x - (target.x + 0.5)
        val dy = maid.y - (target.y + 0.5)
        val dz = maid.z - (target.z + 0.5)
        if (dx * dx + dz * dz > 4.0 || dy * dy > 2.25) return false
        return maid.level().clip(
            ClipContext(
                maid.eyePosition, Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, maid
            )
        ).type != HitResult.Type.BLOCK
    }

    /** 有完整碰撞体（挡路/可站立）；空气、水、草丛、花等返回 false。 */
    private fun isSolid(level: Level, pos: BlockPos): Boolean {
        val state: BlockState = level.getBlockState(pos)
        return !state.isAir && !state.getCollisionShape(level, pos).isEmpty
    }
}
