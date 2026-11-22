package com.oyxdsg.smartmaid.entity.ai.perception.sense

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionBlockUtil
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionUtil
import com.oyxdsg.smartmaid.entity.ai.perception.Sense
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

open class BlockSense : Sense {
    override fun intervalTicks() = 10
    override fun sectionName() = "blocks"
    override fun collect(maid: SmartMaidEntity, data: MaidPerception) {
        val level = maid.level(); val blocks = data.blocks(); val feet = maid.blockPosition()
        writeBlockEntry(blocks, "feet", level, feet); writeBlockEntry(blocks, "below", level, feet.below())
        val eye = maid.eyePosition; val to = eye.add(maid.lookAngle.scale(32.0)); val hit = level.clip(ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, maid))
        if (hit.type != HitResult.Type.MISS) { val facing = JsonObject(); PerceptionUtil.blockPos(facing, hit.blockPos); val state = level.getBlockState(hit.blockPos); facing.addProperty("id", PerceptionUtil.blockId(state)); facing.addProperty("type", PerceptionUtil.classifyBlock(level, hit.blockPos)); blocks.add("facing", facing) }
        if (maid.tickCount % SCAN_EVERY != 0) return
        blocks.add("containers", collectContainers(level, feet)); blocks.add("ores", collectOres(level, feet)); blocks.add("crops", collectCrops(level, feet)); blocks.add("danger", collectDanger(level, feet))
    }
    private fun writeBlockEntry(blocks: JsonObject, key: String, level: Level, pos: BlockPos) { val o = JsonObject(); val state = level.getBlockState(pos); o.addProperty("id", PerceptionUtil.blockId(state)); o.addProperty("type", PerceptionUtil.classifyBlock(level, pos)); blocks.add(key, o) }
    private fun collectContainers(level: Level, center: BlockPos): JsonArray { val arr = JsonArray(); val found = mutableListOf<BlockPos>(); val a = center.offset(-CONTAINER_RANGE, -2, -CONTAINER_RANGE); val b = center.offset(CONTAINER_RANGE, 2, CONTAINER_RANGE); for (p in BlockPos.betweenClosed(a, b)) { if (found.size >= MAX_ENTRIES) break; if (PerceptionBlockUtil.isContainerAt(level, p)) found.add(p.immutable()) }; found.sortBy { center.distSqr(it) }; found.forEach { arr.add(blockEntry(level, it, center)) }; return arr }
    private fun collectOres(level: Level, center: BlockPos): JsonArray { val arr = JsonArray(); val a = center.offset(-ORE_RANGE, -ORE_RANGE, -ORE_RANGE); val b = center.offset(ORE_RANGE, ORE_RANGE, ORE_RANGE); for (p in BlockPos.betweenClosed(a, b)) { if (arr.size() >= MAX_ENTRIES) break; if (PerceptionBlockUtil.isOre(level.getBlockState(p))) arr.add(blockEntry(level, p, center)) }; return arr }
    private fun collectCrops(level: Level, center: BlockPos): JsonArray { val arr = JsonArray(); val a = center.offset(-CROP_RANGE, -1, -CROP_RANGE); val b = center.offset(CROP_RANGE, 1, CROP_RANGE); for (p in BlockPos.betweenClosed(a, b)) { if (arr.size() >= MAX_ENTRIES) break; if (PerceptionBlockUtil.isMatureCrop(level.getBlockState(p))) arr.add(blockEntry(level, p, center)) }; return arr }
    private fun collectDanger(level: Level, center: BlockPos): JsonArray { val arr = JsonArray(); val a = center.offset(-DANGER_RANGE, -2, -DANGER_RANGE); val b = center.offset(DANGER_RANGE, 2, DANGER_RANGE); for (p in BlockPos.betweenClosed(a, b)) { if (arr.size() >= MAX_ENTRIES) break; if (PerceptionBlockUtil.isDangerBlock(level, p)) arr.add(blockEntry(level, p, center)) }; return arr }
    private fun blockEntry(level: Level, p: BlockPos, center: BlockPos): JsonObject { val o = JsonObject(); PerceptionUtil.blockPos(o, p); val state: BlockState = level.getBlockState(p); o.addProperty("id", PerceptionUtil.blockId(state)); o.addProperty("dist", Math.round(kotlin.math.sqrt(center.distSqr(p)) * 10.0) / 10.0); return o }
    companion object { private const val SCAN_EVERY = 20; private const val CONTAINER_RANGE = 8; private const val ORE_RANGE = 6; private const val CROP_RANGE = 6; private const val DANGER_RANGE = 4; private const val MAX_ENTRIES = 6 }
}
