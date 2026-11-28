package com.oyxdsg.smartmaid.entity.ai

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/** A* navigation with a local terrain gate for the straight-line fallback. */
class MaidGroundPathNavigation(mob: Mob, level: Level) : GroundPathNavigation(mob, level) {
    companion object {
        private const val RATIO = 3.0
        private const val MIN_STRAIGHT_NODES = 8.0
        private const val STRAIGHT_EVAL_INTERVAL = 40
        private const val UNKNOWN_RETRY_INTERVAL = 20
        private const val TARGET_REACH_DIST_SQ = 4.0
    }

    val straightNav = MaidStraightNav()
    private var lastStraightEval = -10000
    private var lastUnsafeGapLogTick = -1000
    private var blockedTarget: BlockPos? = null
    private var unknownTarget: BlockPos? = null
    private var retryUnknownAt = 0
    private var lastProbe: Probe? = null

    private data class Probe(val tick: Int, val from: BlockPos, val target: BlockPos, val result: GapProbeResult)

    fun clearGapBlock() {
        blockedTarget = null
        unknownTarget = null
    }

    fun isBlockedByWideGap(target: BlockPos): Boolean = blockedTarget == target

    override fun createPathFinder(maxVisitedNodes: Int): PathFinder {
        this.nodeEvaluator = MaidWalkNodeEvaluator()
        this.nodeEvaluator.setCanPassDoors(true)
        return PathFinder(this.nodeEvaluator, maxVisitedNodes)
    }

    // GroundPathNavigation 26.2 的签名被视为非空，但找不到路径时实际返回 null。
    @Suppress("RETURN_TYPE_MISMATCH_ON_OVERRIDE")
    override fun createPath(pos: BlockPos, accuracy: Int): Path? {
        val maid = mob as SmartMaidEntity
        if (maid.isOrderedToSit || maid.actionExecutor.active()) return null
        if (blockedTarget != pos) blockedTarget = null
        if (unknownTarget == pos && maid.tickCount < retryUnknownAt) return null
        unknownTarget = null

        val allowStraight = !maid.isCombatActive() && !straightNav.recentlyGaveUp(maid.tickCount)
        val ground = terrain(maid, pos)
        if (allowStraight && ground == GapProbeResult.CLEAR && straightNav.target() == pos &&
            maid.tickCount - lastStraightEval < STRAIGHT_EVAL_INTERVAL
        ) return null

        val candidate: Path? = super.createPath(pos, accuracy)
        val path = candidate?.takeIf(::safeEdges)
        if (path != null && reachesTarget(maid, path, pos) &&
            (ground != GapProbeResult.CLEAR || !allowStraight || !isLongDetour(maid, path, pos))
        ) {
            blockedTarget = null
            if (straightNav.isActive) straightNav.clear(maid)
            return path
        }

        when (ground) {
            GapProbeResult.WIDE_DEEP_GAP -> {
                stopWithFallback()
                blockedTarget = pos.immutable()
                if (maid.tickCount - lastUnsafeGapLogTick >= 20) {
                    MaidDebug.log("导航停止：前方四格以上深沟 $pos")
                    lastUnsafeGapLogTick = maid.tickCount
                }
                return null
            }
            GapProbeResult.UNKNOWN -> {
                stopWithFallback()
                blockedTarget = null
                unknownTarget = pos.immutable()
                retryUnknownAt = maid.tickCount + UNKNOWN_RETRY_INTERVAL
                return null
            }
            GapProbeResult.CLEAR -> Unit
        }

        blockedTarget = null
        if (!allowStraight) {
            if (straightNav.isActive) straightNav.clear(maid)
            if (path == null) super.stop()
            return path
        }
        if (straightNav.target() != pos) {
            if (straightNav.isActive) straightNav.clear(maid)
            MaidDebug.log("StraightNav 降级 -> $pos (原版=${path?.nodeCount ?: 0}节点)")
            straightNav.setTarget(pos)
        }
        lastStraightEval = maid.tickCount
        super.stop()
        return null
    }

    // Vanilla's entity overload also delegates to the position overload, so make that single decision explicit.
    @Suppress("RETURN_TYPE_MISMATCH_ON_OVERRIDE")
    override fun createPath(entity: Entity, accuracy: Int): Path? = createPath(entity.blockPosition(), accuracy)

    // Internal breaker/placer operations call vanilla stop() to pause A* without cancelling themselves.
    fun stopWithFallback() {
        super.stop()
        val maid = mob as SmartMaidEntity
        if (straightNav.isActive) straightNav.clear(maid)
        if (!maid.actionExecutor.active()) maid.moveControl.setWait()
    }

    override fun tick() {
        val maid = mob as SmartMaidEntity
        if (maid.isOrderedToSit || maid.actionExecutor.active()) {
            stopWithFallback()
            return
        }
        val target = straightNav.target()
        if (target != null) {
            // Recheck newly visible columns before any straight movement, dig or placement.
            // Let the requesting goal/task retry A* before marking a gap as terminal.
            if (maid.isCombatActive() || terrain(maid, target) != GapProbeResult.CLEAR) {
                stopWithFallback()
                return
            }
            if (straightNav.tick(maid)) straightNav.clear(maid)
            return
        }
        super.tick()
    }

    private fun terrain(maid: SmartMaidEntity, target: BlockPos): GapProbeResult {
        val from = maid.blockPosition()
        lastProbe?.let { if (it.tick == maid.tickCount && it.from == from && it.target == target) return it.result }
        val level = maid.level()
        val result = probeWideGap(from, target, level.minY,
            { level.hasChunk(it.x shr 4, it.z shr 4) },
            { !level.getBlockState(it).getCollisionShape(level, it).isEmpty },
        )
        lastProbe = Probe(maid.tickCount, from, target.immutable(), result)
        return result
    }

    /** The evaluator checks occupancy; cap returned edge geometry to what the maid can execute. */
    private fun safeEdges(path: Path): Boolean {
        for (i in maxOf(1, path.nextNodeIndex) until path.nodeCount) {
            val a = path.getNode(i - 1)
            val b = path.getNode(i)
            val dx = abs(b.x - a.x)
            val dz = abs(b.z - a.z)
            val dy = b.y - a.y
            if (maxOf(dx, dz) <= 1) {
                if (dy !in -3..2) return false
            } else if ((dx != 0 && dz != 0) || maxOf(dx, dz) > 4 || dy !in 0..1) {
                return false
            }
        }
        return true
    }

    private fun isLongDetour(maid: SmartMaidEntity, path: Path, target: BlockPos): Boolean {
        val from = maid.blockPosition()
        val dx = (from.x - target.x).toDouble()
        val dz = (from.z - target.z).toDouble()
        return path.nodeCount - path.nextNodeIndex > maxOf(MIN_STRAIGHT_NODES, sqrt(dx * dx + dz * dz) * RATIO)
    }

    private fun reachesTarget(maid: SmartMaidEntity, path: Path, target: BlockPos): Boolean {
        if (!path.canReach()) return false
        val end = path.endNode?.asBlockPos() ?: return false
        val dx = end.x - target.x
        val dz = end.z - target.z
        if (dx * dx + dz * dz > TARGET_REACH_DIST_SQ || abs(end.y - target.y) > 1) return false
        if (end == target) return true
        val hit = maid.level().clip(
            ClipContext(Vec3.atCenterOf(end), Vec3.atCenterOf(target), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, maid)
        )
        return hit.type != HitResult.Type.BLOCK
    }
}
