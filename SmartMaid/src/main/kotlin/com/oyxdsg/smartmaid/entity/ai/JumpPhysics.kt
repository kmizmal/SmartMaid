package com.oyxdsg.smartmaid.entity.ai

object JumpPhysics {
    const val GRAVITY = 0.08
    const val VERTICAL_DRAG = 0.98
    const val JUMP_VY0 = 0.42
    const val TAKEOFF_WALK = 0.30
    const val TAKEOFF_RUN = 0.55
    private val FLIGHT_TICKS = computeFlightTicks()
    @JvmStatic fun takeoffSpeed(attributeValue: Double, sprint: Boolean): Double = if (sprint) TAKEOFF_RUN else TAKEOFF_WALK
    @JvmStatic fun flightTicks() = FLIGHT_TICKS
    private fun computeFlightTicks(): Int { var y=0.0; var vy=JUMP_VY0; var t=0; while(t<200){ y+=vy; vy=(vy-GRAVITY)*VERTICAL_DRAG; t++; if(y<=0.0) return t }; return 12 }
}
