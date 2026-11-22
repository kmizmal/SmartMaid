package com.oyxdsg.smartmaid.entity.ai.script

import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask

/**
 * 查询原子任务包装（Atomic Command Protocol §3.2）：查询在 start 时同步执行完毕，
 * 立即完成，把结构化 result 交给脚本引擎（变量绑定 / 条件判断用）。
 */
class ScriptQueryTask(cmd: String, private val result: JsonObject?) : MaidAITask(cmd) {
    override fun canStart(maid: SmartMaidEntity): Boolean = true

    override fun start(maid: SmartMaidEntity) {}

    override fun tick(maid: SmartMaidEntity) {}

    override fun isDone(): Boolean = true

    override fun result(): String = result?.toString() ?: ""

    override fun resultJson(): JsonObject? = result
}
