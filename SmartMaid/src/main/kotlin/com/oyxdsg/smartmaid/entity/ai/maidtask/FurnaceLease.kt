package com.oyxdsg.smartmaid.entity.ai.maidtask

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import java.util.Comparator

object FurnaceLease {
    private val LEASES = HashMap<BlockPos, String>()
    @JvmStatic @Synchronized fun leasedByOther(pos: BlockPos, ownerId: String): Boolean = LEASES[pos]?.let { it != ownerId } ?: false
    @JvmStatic @Synchronized fun acquire(pos: BlockPos?, ownerId: String): Boolean {
        if (pos == null) return false
        val owner = LEASES[pos]
        if (owner != null && owner != ownerId) return false
        LEASES[pos.immutable()] = ownerId
        return true
    }
    @JvmStatic @Synchronized fun release(pos: BlockPos?, ownerId: String) {
        if (pos != null && ownerId == LEASES[pos]) LEASES.remove(pos)
    }
    @JvmStatic @Synchronized fun releaseAll(ownerId: String) { LEASES.entries.removeIf { it.value == ownerId } }
    @JvmStatic fun isIdle(level: Level, pos: BlockPos): Boolean = level.getBlockEntity(pos) is AbstractFurnaceBlockEntity && (level.getBlockEntity(pos) as AbstractFurnaceBlockEntity).getItem(0).isEmpty
    @JvmStatic fun findFree(level: Level, center: BlockPos, range: Int, ownerId: String): BlockPos? = BlockPos.betweenClosedStream(center.offset(-range,-range,-range), center.offset(range,range,range)).filter { level.getBlockEntity(it) is AbstractFurnaceBlockEntity }.map(BlockPos::immutable).filter { !leasedByOther(it, ownerId) }.filter { isIdle(level,it) }.min(Comparator.comparingDouble { it.distSqr(center) }).orElse(null)
    @JvmStatic fun anyFurnace(level: Level, center: BlockPos, range: Int): Boolean = BlockPos.betweenClosedStream(center.offset(-range,-range,-range), center.offset(range,range,range)).anyMatch { level.getBlockEntity(it) is AbstractFurnaceBlockEntity }
    @JvmStatic fun clearAll() { LEASES.clear() }
}
