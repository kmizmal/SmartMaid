package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.entity.SmartMaidEntity

class OneShotTask(id: String, private val desc: String, private val action: Runnable) : MaidAITask(id) {
    private var done = false
    override fun canStart(maid: SmartMaidEntity) = true
    override fun start(maid: SmartMaidEntity) { try { action.run() } catch (e: Exception) { SmartMaid.LOGGER.error("OneShot 任务执行异常: " + taskId(), e) }; done = true }
    override fun tick(maid: SmartMaidEntity) {}
    override fun isDone() = done
    override fun result() = desc
}
