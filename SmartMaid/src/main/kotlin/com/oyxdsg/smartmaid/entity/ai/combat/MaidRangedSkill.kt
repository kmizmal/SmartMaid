package com.oyxdsg.smartmaid.entity.ai.combat

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import net.minecraft.core.Holder
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ProjectileWeaponItem
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.function.Predicate

object MaidRangedSkill {
    private const val ARROW_GRAVITY = 0.05
    private const val ARROW_BASE_DAMAGE = 2.0

    @JvmStatic fun canShoot(maid: SmartMaidEntity) = findBestBowSlot(maid) >= 0 && findArrowSlot(maid, maid.maidInventory.getItem(findBestBowSlot(maid))) >= 0
    @JvmStatic fun beginDraw(maid: SmartMaidEntity): Boolean {
        val bowSlot = findBestBowSlot(maid); if (bowSlot < 0 || !MaidActions.swapToMainhand(maid, bowSlot)) return false
        val bow = maid.mainHandItem; if (bow.item !is BowItem || findArrowSlot(maid, bow) < 0) return false
        maid.startUsingItem(InteractionHand.MAIN_HAND); return true
    }
    @JvmStatic fun drawPower(maid: SmartMaidEntity): Float = BowItem.getPowerForTime(maid.ticksUsingItem)
    @JvmStatic fun release(maid: SmartMaidEntity, target: LivingEntity): Boolean {
        val level = maid.level() as? ServerLevel ?: run { maid.stopUsingItem(); return false }
        val bow = maid.mainHandItem; if (bow.item !is BowItem) { maid.stopUsingItem(); return false }
        val arrowSlot = findArrowSlot(maid, bow); if (arrowSlot < 0) { maid.stopUsingItem(); return false }
        val power = BowItem.getPowerForTime(maid.ticksUsingItem); if (power < 0.1f) { maid.stopUsingItem(); return false }
        val arrowStack = maid.maidInventory.getItem(arrowSlot)
        val arrow = MaidArrow(level, maid, bow, arrowStack.copyWithCount(1)); arrow.isCritArrow = power >= 1.0f; arrow.pickup = AbstractArrow.Pickup.DISALLOWED
        val powerEnch = powerLevel(level, bow); if (powerEnch > 0) arrow.setBaseDamage(ARROW_BASE_DAMAGE + powerEnch * 0.5 + 0.5)
        arrow.setPos(maid.x, maid.eyeY - 0.1, maid.z); val dir = aimDirection(maid, target, power); arrow.shoot(dir.x, dir.y, dir.z, power * 3.0f, 1.0f); level.addFreshEntity(arrow)
        arrowStack.shrink(1); bow.hurtAndBreak(1, maid, EquipmentSlot.MAINHAND); maid.stopUsingItem()
        MaidDebug.log("Combat shoot power=${MaidDebug.fmt1(power.toDouble())} powerEnch=$powerEnch target=${target.type.toShortString()}"); return true
    }
    private fun aimDirection(maid: SmartMaidEntity, target: LivingEntity, power: Float): Vec3 {
        val eye = maid.eyePosition; val targetPos = target.eyePosition; val speed = kotlin.math.max(0.1, power * 3.0); val travel = targetPos.distanceTo(eye) / speed
        return targetPos.add(target.deltaMovement.scale(travel)).subtract(eye).add(0.0, 0.5 * ARROW_GRAVITY * travel * travel, 0.0).normalize()
    }
    @JvmStatic fun findBestBowSlot(maid: SmartMaidEntity): Int {
        val inv = maid.maidInventory; var bestSlot = -1; var bestPower = -1
        for (i in 0 until inv.containerSize) { val stack = inv.getItem(i); if (stack.item !is BowItem) continue; val power = powerLevel(maid.level(), stack); if (power > bestPower) { bestPower = power; bestSlot = i } }
        return bestSlot
    }
    @JvmStatic fun findArrowSlot(maid: SmartMaidEntity, weapon: ItemStack): Int {
        val supported = supportedProjectiles(weapon) ?: return -1; val inv = maid.maidInventory
        for (i in 0 until inv.containerSize) if (supported.test(inv.getItem(i))) return i
        return -1
    }
    private fun supportedProjectiles(weapon: ItemStack): Predicate<ItemStack>? = (weapon.item as? ProjectileWeaponItem)?.allSupportedProjectiles
    private fun powerLevel(level: Level, bow: ItemStack): Int { val registry: Registry<Enchantment> = level.registryAccess().lookup(Registries.ENCHANTMENT).orElse(null) ?: return 0; val power: Holder<Enchantment> = registry.getOrThrow(Enchantments.POWER); return EnchantmentHelper.getItemEnchantmentLevel(power, bow) }
}
