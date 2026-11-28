package com.oyxdsg.smartmaid.entity.ai

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator

/**
 * 女仆专用寻路评估器：保留原版逻辑，同时：
 *  - 允许可跳跃跨越的垂直路径：上坡 1.25~1.5 格、下坡 1.5~3 格
 *  - 生成"跨沟"邻居：当前格水平 2~4 格、中间无支撑、对岸可站立 → 连边
 */
class MaidWalkNodeEvaluator : WalkNodeEvaluator() {
    companion object {
        private const val MAX_UP_STEP = 1.5
        private const val MAX_DOWN_DROP = 3.0
        private const val GAP_MIN_DIST = 2
        private const val GAP_MAX_DIST = 4
    }

    override fun isNeighborValid(currentNode: Node?, neighbor: Node): Boolean {
        if (super.isNeighborValid(currentNode, neighbor)) return true
        if (currentNode == null) return false
        if (neighbor.closed || neighbor.type == PathType.BLOCKED) return false
        val floorCurrent = getFloorLevel(BlockPos(currentNode.x, currentNode.y, currentNode.z))
        val floorNeighbor = getFloorLevel(BlockPos(neighbor.x, neighbor.y, neighbor.z))
        val diff = floorNeighbor - floorCurrent
        if (diff > 1.25 && diff <= MAX_UP_STEP) return true
        if (diff < -1.5 && diff >= -MAX_DOWN_DROP) return true
        return false
    }

    override fun getNeighbors(nodeArray: Array<Node>, node: Node): Int {
        val count = super.getNeighbors(nodeArray, node)
        return addGapJumpNeighbors(nodeArray, node, count)
    }

    /** 在原版相邻邻居之外，额外生成"跨沟对岸"邻居，每方向取最短可行跨沟。 */
    private fun addGapJumpNeighbors(nodeArray: Array<Node>, node: Node, startCount: Int): Int {
        var count = startCount
        for (dir in Direction.Plane.HORIZONTAL) {
            if (count >= nodeArray.size - 1) break
            val sx = dir.stepX
            val sz = dir.stepZ
            for (d in GAP_MIN_DIST..GAP_MAX_DIST) {
                if (count >= nodeArray.size - 1) break
                if (!isGapPath(node.x, node.y, node.z, sx, sz, d)) continue
                // 对岸落点：先试同层，再试 +1 层（对岸高 1 格的高台）
                for (up in 0..1) {
                    val to = BlockPos(node.x + sx * d, node.y + up, node.z + sz * d)
                    if (!isLandableCell(to)) continue
                    val floor = getFloorLevel(to)
                    val n = findAcceptedNode(to.x, to.y, to.z, 1, floor, dir, node.type)
                        ?: continue
                    if (n.closed || n.type == PathType.BLOCKED) continue
                    if (containsNode(nodeArray, count, n)) continue
                    nodeArray[count++] = n
                    break
                }
            }
        }
        return count
    }

    /** 沿主轴从当前格到目标格，中间各格均为"沟"（身体空气 + 下方无支撑） */
    private fun isGapPath(x: Int, y: Int, z: Int, sx: Int, sz: Int, dist: Int): Boolean {
        for (i in 1 until dist) {
            val mid = BlockPos(x + sx * i, y, z + sz * i)
            if (!isGapCell(mid)) return false
        }
        return true
    }

    private fun isGapCell(p: BlockPos): Boolean {
        val body: BlockState = currentContext.getBlockState(p)
        val below: BlockState = currentContext.getBlockState(p.below())
        return body.isAir && below.isAir
    }

    /** 对岸格可站立：身体空气、脚下有支撑、头顶净空 */
    private fun isLandableCell(to: BlockPos): Boolean {
        val body: BlockState = currentContext.getBlockState(to)
        val below: BlockState = currentContext.getBlockState(to.below())
        val above: BlockState = currentContext.getBlockState(to.above())
        return body.isAir && !below.isAir && above.isAir
    }

    private fun containsNode(arr: Array<Node>, n: Int, node: Node): Boolean {
        for (i in 0 until n) {
            val o = arr[i]
            if (o != null && o.x == node.x && o.y == node.y && o.z == node.z) return true
        }
        return false
    }
}
