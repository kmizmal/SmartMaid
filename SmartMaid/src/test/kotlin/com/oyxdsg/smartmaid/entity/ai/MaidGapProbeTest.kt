package com.oyxdsg.smartmaid.entity.ai

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MaidGapProbeTest {
    private val origin = BlockPos(0, 66, 0)

    @Test
    fun distantTargetNeverReadsBeyondBudget() {
        var loadedChecks = 0
        var reads = 0
        val verdict = probeWideGap(origin, BlockPos(128, 66, 0), -64, { loadedChecks++; true }) {
            reads++
            it.y == 62
        }
        assertTrue(reads <= 32, "queried $reads block states")
        assertTrue(loadedChecks in 1..8, "checked $loadedChecks columns")
        assertEquals(GapProbeResult.CLEAR, verdict)
    }

    @Test
    fun unloadedColumnDoesNotReadTerrain() {
        val reads = mutableListOf<Int>()
        val verdict = probeWideGap(origin, BlockPos(8, 66, 0), -64, { it.x == 1 }) {
            reads += it.x
            it.y == 65
        }
        assertEquals(listOf(1), reads)
        assertEquals(GapProbeResult.UNKNOWN, verdict)
    }

    @Test
    fun fourShallowColumnsAreNotDeep() {
        assertEquals(GapProbeResult.CLEAR, probeWideGap(origin, BlockPos(5, 66, 0), -64, { true }) {
            it.y == if (it.x in 1..4) 64 else 65
        })
    }

    @Test
    fun fourDeepColumnsAreBlocked() {
        assertEquals(GapProbeResult.WIDE_DEEP_GAP, probeWideGap(origin, BlockPos(5, 66, 0), -64, { true }) {
            it.y == if (it.x in 1..4) 58 else 65
        })
    }

    @Test
    fun nearMinYDoesNotReadOutsideWorld() {
        val ys = mutableListOf<Int>()
        val verdict = probeWideGap(BlockPos(0, -63, 0), BlockPos(5, -63, 0), -64, { true }) {
            ys += it.y
            false
        }
        assertEquals(GapProbeResult.WIDE_DEEP_GAP, verdict)
        assertEquals(List(4) { -64 }, ys)
    }

    @Test
    fun diagonalProbeFollowsStraightStepperAndDoesNotOvershoot() {
        val columns = mutableListOf<Pair<Int, Int>>()
        val verdict = probeWideGap(origin, BlockPos(3, 66, 1), -64, {
            columns += it.x to it.z
            true
        }) { it.y == 65 }
        assertEquals(listOf(1 to 1, 2 to 1, 3 to 1), columns)
        assertEquals(GapProbeResult.CLEAR, verdict)
    }

    @Test
    fun oneSupportedColumnBreaksADeepRun() {
        assertEquals(GapProbeResult.CLEAR, probeWideGap(origin, BlockPos(7, 66, 0), -64, { true }) {
            it.x == 4 && it.y == 62
        })
    }
}
