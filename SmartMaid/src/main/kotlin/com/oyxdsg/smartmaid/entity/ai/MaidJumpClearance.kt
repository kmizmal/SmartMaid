package com.oyxdsg.smartmaid.entity.ai

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/** The calibrated runway may start in a different cell from the path's gap edge. */
internal fun calibratedJumpLaunch(from: BlockPos, to: BlockPos, back: Double): Vec3 {
    val dx = to.x.toDouble() - from.x
    val dz = to.z.toDouble() - from.z
    val distance = sqrt(dx * dx + dz * dz)
    val offset = 0.4 - back
    return Vec3(from.x + 0.5 + dx / distance * offset, from.y + 0.5, from.z + 0.5 + dz / distance * offset)
}

internal enum class JumpPositioning { WAIT, ABORT, FIRE }

internal fun hasJumpClearance(
    launch: BlockPos,
    to: BlockPos,
    isLoaded: (BlockPos) -> Boolean,
    supported: (BlockPos) -> Boolean,
    clear: (BlockPos) -> Boolean,
): Boolean = isLoaded(launch) && isLoaded(to) && supported(launch.below()) && supported(to.below()) &&
    clear(launch) && clear(launch.above()) && clear(to) && clear(to.above())

internal fun jumpPositioning(ready: Boolean, expired: Boolean, clearance: () -> Boolean): JumpPositioning = when {
    expired || !clearance() -> JumpPositioning.ABORT
    ready -> JumpPositioning.FIRE
    else -> JumpPositioning.WAIT
}
