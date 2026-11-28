package com.oyxdsg.smartmaid.entity.ai.combat

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

class MaidArrow(level: Level, owner: LivingEntity, weapon: ItemStack, ammo: ItemStack) : Arrow(level, owner, weapon, ammo) {
    override fun canHitEntity(target: Entity): Boolean {
        if (target is Player) {
            val shooter = getOwner() as? SmartMaidEntity ?: return false
            if (!shooter.settings.friendlyFire) return false
        }
        return super.canHitEntity(target)
    }
}
