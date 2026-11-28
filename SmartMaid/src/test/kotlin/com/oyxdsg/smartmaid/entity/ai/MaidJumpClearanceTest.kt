package com.oyxdsg.smartmaid.entity.ai

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MaidJumpClearanceTest {
    private val from = BlockPos(3, 66, 8)
    private val to = BlockPos(3, 66, 4)

    private fun permits(
        blocked: Set<BlockPos> = emptySet(),
        unsupported: Set<BlockPos> = emptySet(),
        unloaded: Set<BlockPos> = emptySet(),
    ): Boolean = hasJumpClearance(from, to,
        isLoaded = { it !in unloaded },
        supported = { it !in unsupported },
        clear = { it !in blocked },
    )

    @Test fun rejectsSolidLandingCell() = assertFalse(permits(blocked = setOf(to)))
    @Test fun rejectsLowLandingCeiling() = assertFalse(permits(blocked = setOf(to.above())))
    @Test fun rejectsUnsupportedLanding() = assertFalse(permits(unsupported = setOf(to.below())))
    @Test fun rejectsObstructedRunup() = assertFalse(permits(blocked = setOf(from.above())))
    @Test fun openLandingAllowsCalibratedJump() = assertTrue(permits())

    @Test
    fun calibratedRunupChecksRealLaunchBehindTheEdge() {
        val launch = BlockPos.containing(calibratedJumpLaunch(from, to, 1.3))
        assertEquals(from.south(), launch)
        val clearance = { blocked: Set<BlockPos>, unsupported: Set<BlockPos> ->
            hasJumpClearance(launch, to, { true }, { it !in unsupported }, { it !in blocked })
        }
        assertEquals(JumpPositioning.WAIT, jumpPositioning(false, false) { clearance(emptySet(), emptySet()) })
        assertEquals(JumpPositioning.FIRE, jumpPositioning(true, false) { clearance(emptySet(), emptySet()) })
        assertEquals(JumpPositioning.ABORT, jumpPositioning(false, false) { clearance(setOf(launch.above()), emptySet()) })
        assertEquals(JumpPositioning.ABORT, jumpPositioning(true, false) { clearance(emptySet(), setOf(launch.below())) })
    }

    @Test
    fun neverReadsAnUnloadedLanding() {
        val reads = mutableListOf<BlockPos>()
        assertFalse(hasJumpClearance(from, to, { it.z != to.z }, { reads += it; true }, { reads += it; true }))
        assertTrue(reads.none { it.z == to.z })
    }

    @Test
    fun positioningDoesNotJumpAfterLandingObstructed() {
        assertEquals(JumpPositioning.ABORT, jumpPositioning(true, false) { permits(blocked = setOf(to)) })
    }

    @Test
    fun positioningTimeoutAbortsInsteadOfJumping() {
        assertEquals(JumpPositioning.ABORT, jumpPositioning(false, true) { permits() })
        assertEquals(JumpPositioning.WAIT, jumpPositioning(false, false) { permits() })
        assertEquals(JumpPositioning.FIRE, jumpPositioning(true, false) { permits() })
    }
}
