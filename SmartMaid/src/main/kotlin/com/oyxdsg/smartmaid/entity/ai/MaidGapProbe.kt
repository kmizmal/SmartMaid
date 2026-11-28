package com.oyxdsg.smartmaid.entity.ai

import net.minecraft.core.BlockPos

internal enum class GapProbeResult { CLEAR, WIDE_DEEP_GAP, UNKNOWN }

private const val MAX_COLUMNS = 8
private const val UNSAFE_RUN = 4
private const val SUPPORT_DEPTH = 4 // one block below feet, plus three safe drop blocks

/** Only inspects the local straight movement window, never a whole route. */
internal fun probeWideGap(
    from: BlockPos,
    target: BlockPos,
    minY: Int,
    isLoaded: (BlockPos) -> Boolean,
    hasSupport: (BlockPos) -> Boolean,
): GapProbeResult {
    var column = from
    var deepRun = 0
    repeat(MAX_COLUMNS) {
        if (column.x == target.x && column.z == target.z) return GapProbeResult.CLEAR
        column = column.offset(target.x.compareTo(column.x), 0, target.z.compareTo(column.z))
        if (!isLoaded(column)) return GapProbeResult.UNKNOWN
        val supported = (1..SUPPORT_DEPTH).any { depth ->
            val y = from.y - depth
            y >= minY && hasSupport(BlockPos(column.x, y, column.z))
        }
        deepRun = if (supported) 0 else deepRun + 1
        if (deepRun >= UNSAFE_RUN) return GapProbeResult.WIDE_DEEP_GAP
    }
    return GapProbeResult.CLEAR
}
