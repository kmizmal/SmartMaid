package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.google.gson.JsonObject

class QueuedTask(val id: String, var queue: QueueKind, val cmd: String, val params: JsonObject = JsonObject()) {
    enum class QueueKind { LONG, SHORT }
    enum class State { QUEUED, RUNNING, PAUSED, DONE, FAILED, SKIPPED, CANCELLED }
    enum class PauseReason { COMBAT, PREEMPTED, LONGTERM_SUSPEND, USER, BLOCKED }
    var mutexKey: String? = null
    var groupId: String? = null
    var parentLabel: String? = null
    var displayName: String? = null
    var priority: Int = 3
    var state: State = State.QUEUED
    var pauseReason: PauseReason? = null
    var seq: Int = 0
    var interruptCount: Int = 0
    var runAttempts: Int = 0
    var progress: JsonObject? = null
    var enqueuedAtTick: Long = 0
    var startedAtTick: Long = 0
    var lastActiveTick: Long = 0
    var result: String? = null
    init { require(id.isNotEmpty()) { "QueuedTask id 不能为空" } }
    fun id() = id
    fun queue() = queue
    fun cmd() = cmd
    fun params() = params
    fun mutexKey() = mutexKey
    fun groupId() = groupId
    fun parentLabel() = parentLabel
    fun displayName() = displayName
    fun priority() = priority
    fun state() = state
    fun pauseReason() = pauseReason
    fun seq() = seq
    fun interruptCount() = interruptCount
    fun runAttempts() = runAttempts
    fun progress() = progress
    fun enqueuedAtTick() = enqueuedAtTick
    fun startedAtTick() = startedAtTick
    fun lastActiveTick() = lastActiveTick
    fun result() = result
    override fun toString() = "QueuedTask{$id $queue $cmd $state${pauseReason?.let { "/$it" } ?: ""}}"
}
