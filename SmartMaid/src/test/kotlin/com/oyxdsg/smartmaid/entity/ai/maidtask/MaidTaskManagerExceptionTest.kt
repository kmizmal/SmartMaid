package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.QueueKind
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.State
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MaidTaskManagerExceptionTest {
    private class FakeTask(id: String, private val stopThrows: Boolean = false) : MaidAITask(id) {
        var forceStopped = false
        override fun canStart(maid: SmartMaidEntity) = true
        override fun start(maid: SmartMaidEntity) = Unit
        override fun tick(maid: SmartMaidEntity) = Unit
        override fun isDone() = false
        override fun result() = "fake"
        override fun forceStop(maid: SmartMaidEntity?) {
            forceStopped = true
            if (stopThrows) throw IllegalStateException("forceStop failed")
        }
    }

    private fun MaidTaskManager.add(task: FakeTask, cmd: String, kind: QueueKind = QueueKind.SHORT) {
        enqueueTask(task, task.taskId(), cmd, JsonObject(), kind, false, task.taskId())
    }

    @Test
    fun `failed tick advances even if forceStop also fails`() {
        val manager = MaidTaskManager(null)
        val boom = FakeTask("boom", stopThrows = true)
        manager.add(boom, "mine")
        manager.add(FakeTask("next"), "farm")
        val current = manager.queue().startNext(0)!!

        manager.executeTaskTick(boom, current) { throw IllegalStateException("tick failed") }

        assertEquals(State.FAILED, current.state)
        assertEquals("next", manager.queue().current()?.id())
        assertTrue(boom.forceStopped)
        assertFalse(manager.hasRuntimeTask("boom"))
    }

    @Test
    fun `two failed ticks clear only the affected queue and its runtime tasks`() {
        val manager = MaidTaskManager(null)
        val first = FakeTask("first")
        val second = FakeTask("second")
        manager.add(first, "mine")
        manager.add(second, "farm")
        manager.add(FakeTask("third"), "collect")
        manager.add(FakeTask("long"), "guard", QueueKind.LONG)

        val one = manager.queue().startNext(0)!!
        manager.executeTaskTick(first, one) { throw IllegalStateException("first") }
        val two = manager.queue().current()!!
        manager.executeTaskTick(second, two) { throw IllegalStateException("second") }

        assertEquals(State.FAILED, one.state)
        assertEquals(State.FAILED, two.state)
        assertEquals(0, manager.queue().shortSize())
        assertEquals("long", manager.queue().current()?.id())
        assertFalse(manager.hasRuntimeTask("third"))
        assertTrue(first.forceStopped && second.forceStopped)
    }

    @Test
    fun `cancel still marks current cancelled when forceStop fails`() {
        val manager = MaidTaskManager(null)
        val boom = FakeTask("boom", stopThrows = true)
        manager.add(boom, "mine")
        val current = manager.queue().startNext(0)!!
        manager.cancel()
        assertTrue(boom.forceStopped)
        assertEquals(State.CANCELLED, current.state)
        assertNull(manager.queue().current())
        assertFalse(manager.hasRuntimeTask("boom"))
    }

    @Test
    fun `fatal JVM error propagates without marking the task failed`() {
        val manager = MaidTaskManager(null)
        val task = FakeTask("fatal")
        manager.add(task, "mine")
        val current = manager.queue().startNext(0)!!
        val fatal = object : VirtualMachineError("test only") {}
        val thrown = assertThrows(VirtualMachineError::class.java) {
            manager.executeTaskTick(task, current) { throw fatal }
        }
        assertSame(fatal, thrown)
        assertEquals(State.RUNNING, current.state)
        assertFalse(task.forceStopped)
    }
}
