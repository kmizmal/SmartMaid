package com.oyxdsg.smartmaid.entity.ai.perception

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.combat.MaidTargetFilter
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.Container
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.state.BlockState

object PerceptionBlockUtil {
    private val ORE_TAGS = arrayOf("coal_ores", "copper_ores", "iron_ores", "gold_ores", "redstone_ores", "lapis_ores", "diamond_ores", "emerald_ores", "quartz_ores")

    @JvmStatic fun isOre(state: BlockState): Boolean = ORE_TAGS.any { tag -> state.`is`(TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("minecraft", tag))) }
    @JvmStatic fun isMatureCrop(state: BlockState): Boolean = state.block is CropBlock && (state.block as CropBlock).isMaxAge(state)
    @JvmStatic fun isDangerBlock(level: Level, pos: BlockPos): Boolean {
        val block = level.getBlockState(pos).block
        return block == Blocks.LAVA || block == Blocks.FIRE || block == Blocks.MAGMA_BLOCK || block == Blocks.WATER || block == Blocks.CACTUS
    }
    @JvmStatic fun isContainerAt(level: Level, pos: BlockPos): Boolean {
        if (!level.isLoaded(pos)) return false
        return level.getBlockEntity(pos) is Container
    }
    @JvmStatic fun findNearestContainer(level: Level, center: BlockPos, range: Int): BlockPos? {
        val a = center.offset(-range, -2, -range); val b = center.offset(range, 2, range)
        var nearest: BlockPos? = null; var best = Double.MAX_VALUE
        for (p in BlockPos.betweenClosed(a, b)) if (isContainerAt(level, p)) {
            val d = p.distSqr(center); if (d < best) { best = d; nearest = p.immutable() }
        }
        return nearest
    }

    @JvmStatic fun findNearestHostile(maid: SmartMaidEntity, range: Int): LivingEntity? =
        com.oyxdsg.smartmaid.entity.ai.MaidActions.findEntities(maid, LivingEntity::class.java, range.toDouble()) { e -> MaidTargetFilter.isHostile(maid, e) }
            .minWithOrNull(compareBy { maid.distanceToSqr(it) })
    @JvmStatic fun findNearestByType(maid: SmartMaidEntity, range: Int, type: EntityType<*>?): LivingEntity? {
        if (type == null) return null
        return com.oyxdsg.smartmaid.entity.ai.MaidActions.findEntities(maid, LivingEntity::class.java, range.toDouble()) { e -> e.type == type && e.isAlive && e !== maid && e !== maid.owner && e !is Player && maid.canAttack(e) && maid.hasLineOfSight(e) }
            .minWithOrNull(compareBy { maid.distanceToSqr(it) })
    }
}
