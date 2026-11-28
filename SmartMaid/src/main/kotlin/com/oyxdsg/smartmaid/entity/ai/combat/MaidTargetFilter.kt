package com.oyxdsg.smartmaid.entity.ai.combat

import com.oyxdsg.smartmaid.data.SmartMaidConfig
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player

object MaidTargetFilter {
    private val NEVER_TARGET: TagKey<EntityType<*>> = TagKey.create(
        Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("smartmaid", "never_target")
    )

    @JvmStatic
    fun isHostile(maid: SmartMaidEntity, target: LivingEntity?): Boolean {
        if (target == null || target === maid || !target.isAlive) return false
        if (isFriendly(target)) return false
        val type = target.type
        if (type.builtInRegistryHolder().`is`(NEVER_TARGET)) return false
        if (SmartMaidConfig.combatNeverTarget().contains(type)) return false
        return target is Monster && maid.canAttack(target) && maid.hasLineOfSight(target)
    }

    @JvmStatic
    fun isFriendly(entity: Entity): Boolean = entity is Player
}
