package com.oyxdsg.smartmaid.network

object MaidMenuPolicy {
    data class MenuDecision(val isAllowed: Boolean, val isQueue: Boolean)

    private val QUICK_COMMANDS = setOf("mine", "guard", "collect", "eat", "attack", "move", "farm", "cheststore")
    private val QUEUE_OPS = setOf("query", "cancelCurrent", "clear", "stop", "promote", "moveUp", "remove", "stopGroup")

    @JvmStatic
    fun isAllowedQuickCommand(cmd: String?): Boolean = cmd in QUICK_COMMANDS

    @JvmStatic
    fun isAllowedQueueOp(op: String?): Boolean = op in QUEUE_OPS

    @JvmStatic
    fun classify(op: String?, cmd: String?): MenuDecision =
        if (op == "enqueue") MenuDecision(isAllowedQuickCommand(cmd), true)
        else MenuDecision(isAllowedQueueOp(op), false)
}
