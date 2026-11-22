package com.oyxdsg.smartmaid.entity.ai

object JumpPhysics {
    const val GRAVITY = 0.08
    const val VERTICAL_DRAG = 0.98
    const val AIR_INERTIA = 0.91
    const val AIR_ACCELERATION = 0.02
    const val JUMP_VY0 = 0.42
    const val SPRINT_JUMP_BOOST = 0.2
    const val PLAYER_MOVEMENT_SPEED = 0.1
    const val SPRINT_MULTIPLIER = 1.3
    @JvmField val GROUND_SPEED_FACTOR = 1.0 / (1.0 - 0.6 * AIR_INERTIA)
    @JvmField val GROUND_VEL_FACTOR = (0.6 * AIR_INERTIA) / (1.0 - 0.6 * AIR_INERTIA)
    const val TAKEOFF_WALK = 0.30
    const val TAKEOFF_RUN = 0.55
    class TrajPoint(@JvmField val x: Double, @JvmField val y: Double, @JvmField val tick: Int)
    private val FLIGHT_TICKS = computeFlightTicks()
    @JvmStatic fun groundVelocity(attributeValue: Double, sprint: Boolean): Double = attributeValue * (if (sprint) SPRINT_MULTIPLIER else 1.0) * GROUND_VEL_FACTOR
    @JvmStatic fun takeoffSpeed(attributeValue: Double, sprint: Boolean): Double = if (sprint) TAKEOFF_RUN else TAKEOFF_WALK
    @JvmStatic fun flightTicks() = FLIGHT_TICKS
    @JvmStatic fun simulateTrajectory(takeoffSpeed: Double, airInput: Boolean, maxTicks: Int): Array<TrajPoint> { var v=takeoffSpeed; var x=0.0; var y=0.0; var vy=JUMP_VY0; val points=ArrayList<TrajPoint>(); for(t in 0 until maxTicks){ x+=v; y+=vy; points.add(TrajPoint(x,y,t+1)); v=v*AIR_INERTIA+(if(airInput) AIR_ACCELERATION else 0.0); vy=(vy-GRAVITY)*VERTICAL_DRAG; if(y<=0.0&&t>0) break }; return points.toTypedArray() }
    private fun computeFlightTicks(): Int { var y=0.0; var vy=JUMP_VY0; var t=0; while(t<200){ y+=vy; vy=(vy-GRAVITY)*VERTICAL_DRAG; t++; if(y<=0.0) return t }; return 12 }
}
