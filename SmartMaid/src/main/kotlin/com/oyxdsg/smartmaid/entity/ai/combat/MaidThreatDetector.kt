package com.oyxdsg.smartmaid.entity.ai.combat

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.phys.AABB

object MaidThreatDetector {
    private const val ARROW_SCAN_RANGE = 16.0
    private const val ARROW_PREDICT_TICKS = 30
    private const val ARROW_HITBOX_INFLATE = 0.8
    private const val CREEPER_RANGE = 4.0
    private const val CREEPER_SWELL_THRESHOLD = 0.6f
    @JvmStatic fun threatLabel(maid: SmartMaidEntity): String = when { arrowIncoming(maid) -> "arrow"; creeperAboutToExplode(maid) -> "creeper"; else -> "" }
    @JvmStatic fun arrowIncoming(maid: SmartMaidEntity): Boolean { val box = maid.boundingBox.inflate(ARROW_HITBOX_INFLATE); for (arrow in scanArrows(maid)) if (firstHitTick(box, arrow) > 0) return true; return false }
    private fun scanArrows(maid: SmartMaidEntity): List<AbstractArrow> = MaidActions.findEntities(maid, AbstractArrow::class.java, ARROW_SCAN_RANGE) { it.isAlive && it.owner !== maid }
    private fun firstHitTick(box: AABB, arrow: AbstractArrow): Int { val pos = arrow.position(); val vel = arrow.deltaMovement; if (vel.lengthSqr() < 1.0E-4) return -1; for (t in 1..ARROW_PREDICT_TICKS) if (box.contains(pos.add(vel.scale(t.toDouble())))) return t; return -1 }
    @JvmStatic fun debug(maid: SmartMaidEntity) {
        if (!MaidDebug.enabled() || maid.tickCount % 10 != 0) return
        val box = maid.boundingBox.inflate(ARROW_HITBOX_INFLATE); val arrows = scanArrows(maid); if (arrows.isEmpty()) return
        val sb = StringBuilder(); sb.append("CombatShield threat=").append(threatLabel(maid).ifEmpty { "-" }).append(" hasShield=").append(MaidShieldSkill.hasShield(maid)).append(" blocking=").append(maid.isBlocking).append(" yaw=").append(fmt(maid.yRot.toDouble())).append(" headRot=").append(fmt(maid.yHeadRot.toDouble()))
        for (a in arrows) {
            val v = a.deltaMovement; val owner: Entity? = a.owner; val hitIn = firstHitTick(box, a); sb.append(" | arrow#").append(a.id).append(" type=").append(a.type.toShortString()).append(" pos=(").append(a.blockPosition().x).append(',').append(a.blockPosition().y).append(',').append(a.blockPosition().z).append(") vel=(").append(fmt(v.x)).append(',').append(fmt(v.y)).append(',').append(fmt(v.z)).append(") dist=").append(fmt(a.distanceTo(maid).toDouble())).append(" owner=").append(owner?.type?.toShortString() ?: "?").append(" hitIn=").append(if (hitIn > 0) "${hitIn}t" else "-") }
        MaidDebug.log(sb.toString())
    }
    @JvmStatic fun creeperAboutToExplode(maid: SmartMaidEntity): Boolean = MaidActions.findEntities(maid, Creeper::class.java, CREEPER_RANGE) { it.isAlive && it.swellDir > 0 && it.getSwelling(1.0f) > CREEPER_SWELL_THRESHOLD }.isNotEmpty()
    private fun fmt(v: Double) = String.format("%.1f", v)
}
