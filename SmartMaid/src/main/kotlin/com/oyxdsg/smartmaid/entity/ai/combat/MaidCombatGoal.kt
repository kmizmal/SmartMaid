package com.oyxdsg.smartmaid.entity.ai.combat

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.item.BowItem
import java.util.EnumSet

class MaidCombatGoal(private val maid: SmartMaidEntity) : Goal() {
    private companion object {
        const val MELEE_DISTANCE = 3.0
        const val TARGET_LOST_TIMEOUT = 60
        const val FAILED_ATTACK_COOLDOWN = 10
        const val EAT_SAFE_DISTANCE = 7.0
        const val EAT_COOLDOWN = 34
        const val EAT_RETREAT_LIMIT = 40
        const val RANGED_MIN_DISTANCE = 6.0
        const val KITE_DISTANCE = 10.0
        const val RANGED_COOLDOWN = 24
        const val RANGED_RELEASE_COOLDOWN = 6
        const val VERBOSE_INTERVAL = 10
    }

    private enum class Mode { APPROACH, MELEE, RANGED, EAT, SHIELD }

    private val movement = MaidCombatMovement()
    private var target: LivingEntity? = null
    private var actionCooldown = 0
    private var lostTicks = 0
    private var eatRetreatTicks = 0
    private var engaged = false
    private var blockStartTick = -1
    private var lastLoggedTarget: LivingEntity? = null
    private var lastMode: Mode? = null
    private var stopReason = "?"

    init { flags = EnumSet.of(Flag.MOVE, Flag.LOOK) }

    fun isEngaged(): Boolean = engaged

    override fun canUse(): Boolean {
        if (maid.isOrderedToSit) return false
        val candidate = maid.target ?: return false
        if (!candidate.isAlive || !maid.canAttack(candidate)) return false
        target = candidate
        return true
    }

    override fun canContinueToUse(): Boolean {
        if (maid.isOrderedToSit) { stopReason = "sit"; return false }
        val current = target ?: run { stopReason = "no_target"; return false }
        if (!current.isAlive) { stopReason = "target_dead"; return false }
        if (!maid.canAttack(current)) { stopReason = "cannot_attack"; return false }
        return true
    }

    override fun start() {
        engaged = true
        actionCooldown = 0
        lostTicks = 0
        lastMode = null
        lastLoggedTarget = null
        MaidDebug.log("Combat start target=${describe(target)}")
    }

    override fun stop() {
        engaged = false
        target = null
        if (maid.isUsingItem) maid.stopUsingItem()
        maid.navigation.stop()
        maid.moveControl.setWait()
        MaidDebug.log("Combat stop reason=$stopReason")
    }

    override fun tick() {
        val current = target
        if (current == null || !current.isAlive) {
            if (++lostTicks > TARGET_LOST_TIMEOUT) maid.target = null
            return
        }
        lostTicks = 0
        maid.lookControl.setLookAt(current, 30.0f, maid.maxHeadXRot.toFloat())
        val dist = maid.distanceTo(current).toDouble()
        if (current !== lastLoggedTarget) {
            lastLoggedTarget = current
            MaidDebug.log("Combat target=${describe(current)} dist=${MaidDebug.fmt1(dist)}")
        }
        if (MaidDebug.verbose() && maid.tickCount % VERBOSE_INTERVAL == 0) {
            MaidDebug.log("Combat tick mode=$lastMode dist=${MaidDebug.fmt1(dist)} cd=$actionCooldown hp=${MaidDebug.fmt1(maid.health.toDouble())} food=${maid.maidFood.getFoodLevel()} mainhand=${MaidDebug.itemName(maid.mainHandItem)}")
        }
        if (handleShield(dist)) return
        if (maid.isUsingItem && maid.useItem.item is BowItem && !MaidRangedSkill.canShoot(maid)) {
            MaidDebug.log("Combat 松弦（无箭/弓被换走）")
            maid.stopUsingItem()
        }
        if (shouldEat()) { handleEat(current, dist); return }
        eatRetreatTicks = 0
        if (dist > RANGED_MIN_DISTANCE && MaidRangedSkill.canShoot(maid)) { handleRanged(current, dist); return }
        if (maid.isWithinMeleeAttackRange(current)) {
            logMode(Mode.MELEE, dist)
            maid.navigation.stop()
            movement.keepDistance(maid, current, MELEE_DISTANCE, SmartMaidEntity.MAID_ATTACK_REACH)
            if (actionCooldown > 0) { actionCooldown--; return }
            MaidWeaponSelector.equipBestMelee(maid)
            if (MaidActions.attack(maid, current)) {
                maid.maidFood.addExhaustion(0.1f)
                actionCooldown = MaidWeaponSelector.meleeCooldownTicks(maid)
                MaidDebug.log("Combat melee attack cd=$actionCooldown weapon=${MaidDebug.itemName(maid.mainHandItem)}")
            } else actionCooldown = FAILED_ATTACK_COOLDOWN
        } else {
            logMode(Mode.APPROACH, dist)
            if (maid.navigation.isDone || maid.tickCount % 10 == 0) maid.navigation.moveTo(current, 1.0)
        }
    }

    private fun handleShield(dist: Double): Boolean {
        val blocking = maid.isUsingItem && maid.usedItemHand == InteractionHand.OFF_HAND
        val threat = if (MaidShieldSkill.hasShield(maid)) MaidThreatDetector.threatLabel(maid) else ""
        MaidThreatDetector.debug(maid)
        if (threat.isNotEmpty()) {
            logMode(Mode.SHIELD, dist)
            if (!blocking && MaidShieldSkill.equipShield(maid) && MaidShieldSkill.beginBlock(maid)) {
                blockStartTick = maid.tickCount
                MaidDebug.log("Combat block 举盾 threat=$threat dist=${MaidDebug.fmt1(dist)} yaw=${MaidDebug.fmt1(maid.yRot.toDouble())} headRot=${MaidDebug.fmt1(maid.yHeadRot.toDouble())} t=${maid.tickCount}")
            }
            maid.navigation.stop(); maid.moveControl.setWait(); return true
        }
        if (blocking) {
            maid.stopUsingItem()
            val holdTicks = if (blockStartTick >= 0) maid.tickCount - blockStartTick else -1
            blockStartTick = -1
            MaidDebug.log("Combat block end 收盾 holdTicks=$holdTicks t=${maid.tickCount}")
        }
        return false
    }

    private fun shouldEat() = maid.health < maid.maxHealth * 0.5f && MaidActions.findFoodSlot(maid, null) >= 0

    private fun handleEat(target: LivingEntity, dist: Double) {
        logMode(Mode.EAT, dist); maid.navigation.stop()
        if (dist < EAT_SAFE_DISTANCE && eatRetreatTicks < EAT_RETREAT_LIMIT) {
            eatRetreatTicks++; movement.keepDistance(maid, target, EAT_SAFE_DISTANCE + 1.0)
            if (eatRetreatTicks == EAT_RETREAT_LIMIT) MaidDebug.log("Combat eat 无法拉开距离（墙角？），改为原地进食")
            return
        }
        if (maid.isUsingItem) { movement.keepDistance(maid, target, EAT_SAFE_DISTANCE); return }
        if (actionCooldown > 0) { actionCooldown--; return }
        val slot = MaidActions.findFoodSlot(maid, null)
        if (slot < 0 || !MaidActions.swapToMainhand(maid, slot) || !MaidActions.startEating(maid)) { MaidDebug.log("Combat eat 失败（无可用食物）"); actionCooldown = FAILED_ATTACK_COOLDOWN * 2; return }
        actionCooldown = EAT_COOLDOWN
        MaidDebug.log("Combat eat start dist=${MaidDebug.fmt1(dist)} item=${MaidDebug.itemName(maid.mainHandItem)}")
    }

    private fun handleRanged(target: LivingEntity, dist: Double) {
        logMode(Mode.RANGED, dist); maid.navigation.stop()
        if (dist < KITE_DISTANCE) movement.keepDistance(maid, target, KITE_DISTANCE) else maid.moveControl.setWait()
        if (maid.isUsingItem) {
            if (MaidRangedSkill.drawPower(maid) >= 1.0f || maid.ticksUsingItem >= RANGED_COOLDOWN) if (MaidRangedSkill.release(maid, target)) actionCooldown = RANGED_RELEASE_COOLDOWN
        } else if (actionCooldown > 0) actionCooldown-- else if (MaidRangedSkill.beginDraw(maid)) MaidDebug.log("Combat draw bow dist=${MaidDebug.fmt1(dist)}") else actionCooldown = FAILED_ATTACK_COOLDOWN
    }

    private fun logMode(mode: Mode, dist: Double) { if (mode != lastMode) { lastMode = mode; MaidDebug.log("Combat mode=$mode dist=${MaidDebug.fmt1(dist)}") } }
    private fun describe(entity: LivingEntity?) = entity?.let { "${it.type.toShortString()}@${it.blockPosition()}" } ?: "null"
}
