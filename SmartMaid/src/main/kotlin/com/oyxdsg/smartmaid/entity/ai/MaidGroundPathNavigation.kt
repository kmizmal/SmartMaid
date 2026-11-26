package com.oyxdsg.smartmaid.entity.ai

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * 女仆专用地面导航：注入 [MaidWalkNodeEvaluator]（支持跳跃跨障碍/沟），并在原版寻路与
 * 物理直线路径 [MaidStraightNav] 之间动态切换：
 *  - A* 没路 / 没真正到目标 / 明显绕远（节点数 > 直线距离 × RATIO）→ 切直线模式
 *  - 直线模式每 STRAIGHT_EVAL_INTERVAL tick 重跑 A* 评估，代价回落阈值内 → 切回原版
 */
class MaidGroundPathNavigation(mob: Mob, level: Level) : GroundPathNavigation(mob, level) {
    companion object {
        /** 原版节点数超过"直线距离 × 该倍数"视为绕远 */
        private const val RATIO = 3.0
        /** 直线距离太短（格）不折腾 */
        private const val MIN_STRAIGHT_NODES = 8.0
        /** 直线模式中每多少 tick 重跑一次 A* 评估是否切回 */
        private const val STRAIGHT_EVAL_INTERVAL = 40
        /** 路径终点距目标水平距离超过该值视为"没到目标"（2 格） */
        private const val TARGET_REACH_DIST_SQ = 4.0
    }

    /** 公开 val：Java 侧生成 getStraightNav()，Kotlin 侧可用 .straightNav（MaidMoveControl 依赖）。 */
    val straightNav = MaidStraightNav()
    private var lastStraightEval = -10000

    override fun createPathFinder(maxVisitedNodes: Int): PathFinder {
        this.nodeEvaluator = MaidWalkNodeEvaluator()
        this.nodeEvaluator.setCanPassDoors(true)
        return PathFinder(this.nodeEvaluator, maxVisitedNodes)
    }

    // GroundPathNavigation 26.2 的签名被视为非空，但找不到路径时实际返回 null。
    @Suppress("RETURN_TYPE_MISMATCH_ON_OVERRIDE")
    override fun createPath(pos: BlockPos, accuracy: Int): Path? {
        val maid = this.mob as SmartMaidEntity
        if (maid.isOrderedToSit) return null
        if (combatDisablesStraight(maid)) return super.createPath(pos, accuracy)
        // 跳跃执行器定位/起跳期间不要重新启用直线降级，否则会覆盖 MoveControl 的起跳边缘目标。
        if (maid.actionExecutor.active()) return super.createPath(pos, accuracy)
        if (this.straightNav.recentlyGaveUp(maid.tickCount)) return super.createPath(pos, accuracy)
        if (this.straightNav.isActive && maid.tickCount - this.lastStraightEval < STRAIGHT_EVAL_INTERVAL) {
            return null
        }
        // 原版无路时会返回 null，保留给下面的直线回退判断。
        val path: Path? = super.createPath(pos, accuracy)
        if (useStraight(maid, path, pos)) {
            MaidDebug.log(
                "StraightNav 降级 -> $pos" +
                    " (直线=${Math.round(horizontalDist(maid.blockPosition(), pos))}" +
                    "格 原版=${if (path == null) "无路" else "${path.nodeCount}节点"})"
            )
            this.straightNav.setTarget(pos)
            this.lastStraightEval = maid.tickCount
            return null
        }
        if (this.straightNav.isActive) {
            MaidDebug.log(
                "StraightNav 切回原版 -> $pos" +
                    " (原版=${if (path == null) "无路" else "${path.nodeCount}节点"})"
            )
        }
        this.straightNav.clear(maid)
        return path
    }

    @Suppress("RETURN_TYPE_MISMATCH_ON_OVERRIDE")
    override fun createPath(entity: Entity, accuracy: Int): Path? {
        val maid = this.mob as SmartMaidEntity
        if (maid.isOrderedToSit) return null
        if (combatDisablesStraight(maid)) return super.createPath(entity, accuracy)
        // 跳跃执行器定位/起跳期间不要重新启用直线降级。
        if (maid.actionExecutor.active()) return super.createPath(entity, accuracy)
        val target = entity.blockPosition()
        if (this.straightNav.recentlyGaveUp(maid.tickCount)) return super.createPath(entity, accuracy)
        if (this.straightNav.isActive && maid.tickCount - this.lastStraightEval < STRAIGHT_EVAL_INTERVAL) {
            return null
        }
        val path: Path? = super.createPath(entity, accuracy)
        if (useStraight(maid, path, target)) {
            this.straightNav.setTarget(target)
            this.lastStraightEval = maid.tickCount
            return null
        }
        if (this.straightNav.isActive) {
            MaidDebug.log("StraightNav 切回原版(追实体) -> $target")
        }
        this.straightNav.clear(maid)
        return path
    }

    override fun tick() {
        val maid = this.mob as SmartMaidEntity
        // 坐下 = 原地不动：清掉降级与原版路径，本 tick 不驱动任何移动
        if (maid.isOrderedToSit) {
            if (this.straightNav.isActive) this.straightNav.clear(maid)
            this.stop()
            return
        }
        // 跳跃执行器接管期间，原版 PathNavigation 不能再向 MoveControl 写路径目标。
        if (maid.actionExecutor.active()) {
            this.stop()
            return
        }
        if (this.straightNav.isActive) {
            if (this.straightNav.tick(maid)) this.straightNav.clear(maid)
            return
        }
        super.tick()
    }

    /** 战斗期禁用直线降级：清掉残留目标，一律交原版寻路 + 跳跃执行器。 */
    private fun combatDisablesStraight(maid: SmartMaidEntity): Boolean {
        if (maid.isCombatActive()) {
            if (this.straightNav.isActive) {
                MaidDebug.log("Combat 禁用直线降级（战斗期只走原版寻路，不挖不搭）")
            }
            this.straightNav.clear(maid)
            return true
        }
        return false
    }

    /** 是否应该走直线模式：没路 / 部分路径（到不了目标） / 原版明显绕远。 */
    private fun useStraight(maid: SmartMaidEntity, path: Path?, target: BlockPos): Boolean {
        if (path == null) return true
        if (!reachesTarget(maid, path, target)) return true
        val straight = horizontalDist(maid.blockPosition(), target)
        val threshold = Math.max(MIN_STRAIGHT_NODES, straight * RATIO)
        return path.nodeCount > threshold
    }

    private fun horizontalDist(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dz * dz)
    }

    /** 路径终点是否真正接近目标。高度差大（坡/爬升）视为原版路径合理可达。 */
    private fun reachesTarget(maid: SmartMaidEntity, path: Path, target: BlockPos): Boolean {
        val end: Node = path.endNode ?: return false
        val e = end.asBlockPos()
        val dx = e.x - target.x
        val dz = e.z - target.z
        if (dx * dx + dz * dz > TARGET_REACH_DIST_SQ) return false
        // 高度差大（坡/爬升）：原版路径合理，不降级
        if (Math.abs(e.y - target.y) > 1.5) return true
        // 同高且近：做视线检测，被方块挡住（隔墙）则视为没到目标
        val hit = maid.level().clip(
            ClipContext(
                Vec3.atCenterOf(e), Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, maid
            )
        )
        return hit.type != HitResult.Type.BLOCK
    }
}
