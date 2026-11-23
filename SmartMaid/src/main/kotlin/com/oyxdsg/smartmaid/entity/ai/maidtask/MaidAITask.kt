package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity

abstract class MaidAITask protected constructor(private val id: String) {
    fun taskId(): String = id
    abstract fun canStart(maid: SmartMaidEntity): Boolean
    abstract fun start(maid: SmartMaidEntity)
    abstract fun tick(maid: SmartMaidEntity)
    abstract fun isDone(): Boolean
    abstract fun result(): String
    open fun resultJson(): JsonObject? = null
    open fun isContinuous(): Boolean = false
    open fun isBlocked(): Boolean = false
    open fun progressToken(): String? = null
    open fun onSuspend() {}
    open fun forceStop(maid: SmartMaidEntity?) {}
}
