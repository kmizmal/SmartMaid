package com.oyxdsg.smartmaid.entity.ai

object MaidJumpTable {
    @JvmRecord
    data class JumpSolution(val sprint: Boolean, val back: Double)
    private val TABLE = arrayOfNulls<JumpSolution>(40)
    private fun put(dist:Int, dy:Int, s:JumpSolution){ TABLE[dist*8+(dy+3)] = s }
    init { put(2,-3,JumpSolution(false,1.25)); put(2,-2,JumpSolution(false,1.25)); put(2,-1,JumpSolution(false,1.25)); put(2,0,JumpSolution(false,1.25)); put(2,1,JumpSolution(false,0.0)); put(3,-3,JumpSolution(false,.25)); put(3,-2,JumpSolution(false,.25)); put(3,-1,JumpSolution(false,.25)); put(3,0,JumpSolution(false,.25)); put(3,1,JumpSolution(false,0.0)); put(4,-3,JumpSolution(false,0.0)); put(4,-2,JumpSolution(false,0.0)); put(4,-1,JumpSolution(false,0.0)); put(4,0,JumpSolution(true,1.25)); put(4,1,JumpSolution(true,0.0)) }
    @JvmStatic fun get(dist:Int, dy:Int): JumpSolution? = if(dist !in 2..4 ||dy< -3||dy>1) null else TABLE[dist*8+(dy+3)]
}
