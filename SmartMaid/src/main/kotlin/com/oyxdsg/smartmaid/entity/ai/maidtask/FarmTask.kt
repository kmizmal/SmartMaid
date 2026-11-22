package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionBlockUtil
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * 耕作任务（M3）：区域内收成熟作物 + 空地播种，区域处理完结束。
 *
 * 播种用通用 MaidActions.useItemOn（种子右键耕地，26.2 已移除 ItemNameBlockItem，不依赖具体类）。
 */
open class FarmTask(private val center: BlockPos?, private val range: Int) : MaidAITask("farm"), Resumable {

    private var done: Boolean = false

    override fun canStart(maid: SmartMaidEntity): Boolean = this.center != null

    override fun start(maid: SmartMaidEntity) {
        this.done = false
        MaidDebug.log("Farm start center=" + center + " range=" + range)
    }

    override fun tick(maid: SmartMaidEntity) {
        // 1. 优先收成熟作物
        val crop = findNearestMatureCrop(maid)
        if (crop != null) {
            handlePosition(maid, crop)
            return
        }
        // 2. 无成熟作物 → 找空地播种
        val plantPos = findPlantableSpot(maid)
        if (plantPos != null) {
            handlePlant(maid, plantPos)
            return
        }
        // 3. 无成熟作物且无空地 → 完成
        this.done = true
    }

    override fun isDone(): Boolean = this.done

    override fun result(): String = "耕作区域处理完毕"

    // ---------- Resumable（Q7） ----------

    override fun saveState(): JsonObject {
        val o = JsonObject()
        o.addProperty("version", stateVersion())
        this.center?.let { o.add("center", TaskState.pos(it)) }
        o.addProperty("range", this.range)
        o.addProperty("done", this.done)
        return o
    }

    override fun restoreState(state: JsonObject?) {
        if (state == null) return
        this.done = state.has("done") && state.get("done").getAsBoolean()
    }

    override fun stateVersion(): Int = 1

    override fun validateState(maid: SmartMaidEntity?, state: JsonObject?): Boolean =
        this.center != null && maid != null

    private fun handlePosition(maid: SmartMaidEntity, pos: BlockPos) {
        if (MaidActions.isWithinReach(maid, pos, 3.0)) {
            MaidActions.breakBlock(maid, pos) // 收获：破坏作物，掉落进背包
        } else if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
            MaidActions.navigateTo(maid, pos, 1.0)
        }
    }

    private fun handlePlant(maid: SmartMaidEntity, pos: BlockPos) {
        // 主手不是可播种方块（种子是 BlockItem）→ 从背包换
        if (maid.getMainHandItem().item !is BlockItem) {
            val equipped = MaidActions.equipFromBackpack(maid) { stack -> stack.item is BlockItem }
            if (!equipped) {
                // 没有种子可播 → 视为处理完（无法继续）
                this.done = true
                return
            }
        }
        if (MaidActions.isWithinReach(maid, pos, 3.0)) {
            MaidActions.useItemOn(maid, pos.below(), Direction.UP) // 右键耕地播种
        } else if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
            MaidActions.navigateTo(maid, pos, 1.0)
        }
    }

    /** 区域内找最近成熟作物 */
    private fun findNearestMatureCrop(maid: SmartMaidEntity): BlockPos? =
        scanArea(maid)
            .filter { isMatureCrop(maid.level().getBlockState(it)) }
            .minByOrNull { it.distSqr(maid.blockPosition()) }

    /** 区域内找可播种空地：脚下是耕地、上方是空气 */
    private fun findPlantableSpot(maid: SmartMaidEntity): BlockPos? {
        for (pos in scanArea(maid)) {
            val ground = maid.level().getBlockState(pos)
            if (!ground.isAir) {
                continue
            }
            val farmland = maid.level().getBlockState(pos.below())
            if (farmland.`is`(Blocks.FARMLAND)) {
                return pos
            }
        }
        return null
    }

    companion object {
        /** 是否为成熟作物。实现已收拢到感知工具 PerceptionBlockUtil。 */
        @JvmStatic
        fun isMatureCrop(state: BlockState): Boolean = PerceptionBlockUtil.isMatureCrop(state)
    }

    private fun scanArea(maid: SmartMaidEntity): List<BlockPos> {
        val center = this.center ?: return emptyList()
        val a = center.offset(-this.range, -1, -this.range)
        val b = center.offset(this.range, 1, this.range)
        val list = ArrayList<BlockPos>()
        for (pos in BlockPos.betweenClosed(a, b)) {
            list.add(pos.immutable())
        }
        return list
    }
}
