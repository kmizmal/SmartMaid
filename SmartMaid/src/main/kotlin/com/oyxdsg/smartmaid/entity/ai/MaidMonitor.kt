package com.oyxdsg.smartmaid.entity.ai

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * 移动调试监测：同时输出玩家与女仆的坐标/按键状态 + 各自周围地形 ASCII 俯视图。
 * 每 100 tick（5s）一行状态、地形俯视图由 [MaidDebug.verbose] 单独开启。
 *
 * 方块字符：# 实心 / + 半高 / - 低矮 / ~ 水 / L 岩浆火 / . 空气可走 / @ 实体位置
 */
object MaidMonitor {
    private const val PLAYER_R = 10
    private const val MAID_R = 6

    @JvmStatic
    fun tick(maid: SmartMaidEntity) {
        if (!MaidDebug.verbose()) return
        if (maid.tickCount % 100 != 0) return
        val level = maid.level()
        if (level.isClientSide) return

        val owner = maid.owner
        if (owner is Player) {
            logEntityState("PLAYER", owner)
            logTerrain(level, owner.blockPosition(), "PLAYER", PLAYER_R, owner)
        }
        logEntityState("MAID", maid)
        logTerrain(level, maid.blockPosition(), "MAID", MAID_R, maid)
    }

    /** 输出实体状态：坐标 + 移动输入（按键） + 速度 */
    private fun logEntityState(tag: String, e: LivingEntity) {
        MaidDebug.log(
            "$tag pos=(${fmt(e.x)},${fmt(e.y)},${fmt(e.z)}" +
                " onGround=${e.onGround()}" +
                " sprint=${e.isSprinting}" +
                " jump=${e.isJumping}" +
                " zza=${fmt(e.zza.toDouble())}" +
                " xxa=${fmt(e.xxa.toDouble())}" +
                " yRot=${fmt(e.yRot.toDouble())}" +
                " vel=(${fmt(e.deltaMovement.x)},${fmt(e.deltaMovement.y)},${fmt(e.deltaMovement.z)})"
        )
    }

    /** 输出实体周围地形 ASCII 俯视图（实体所在 y 层） */
    private fun logTerrain(level: Level, center: BlockPos, tag: String, r: Int, self: LivingEntity) {
        MaidDebug.log(
            "terrain[$tag] @ (${center.x},${center.y},${center.z}) R=$r" +
                " (x:${center.x - r}..${center.x + r} z:${center.z - r}..${center.z + r})"
        )
        val selfX = self.blockPosition().x - center.x
        val selfZ = self.blockPosition().z - center.z
        for (dz in r downTo -r) {
            val row = StringBuilder()
            for (dx in -r..r) {
                if (dx == selfX && dz == selfZ) {
                    row.append('@')
                } else {
                    row.append(charFor(level, center.offset(dx, 0, dz)))
                }
            }
            MaidDebug.log("  $row")
        }
    }

    private fun charFor(level: Level, pos: BlockPos): Char {
        val state: BlockState = level.getBlockState(pos)
        if (state.isAir) return '.'
        if (state.fluidState.typeHolder().`is`(FluidTags.WATER)) return '~'
        if (state.fluidState.typeHolder().`is`(FluidTags.LAVA) || state.block == Blocks.FIRE) return 'L'
        val shape: VoxelShape = state.getCollisionShape(level, pos)
        if (shape.isEmpty) return '.'
        val maxY = shape.max(Direction.Axis.Y)
        if (maxY >= 0.95) return '#'
        if (maxY >= 0.4) return '+'
        return '-'
    }

    private fun fmt(v: Double): String = String.format("%.2f", v)
}
