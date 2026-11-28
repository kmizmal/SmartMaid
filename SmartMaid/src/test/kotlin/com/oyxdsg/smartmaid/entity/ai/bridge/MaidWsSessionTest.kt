package com.oyxdsg.smartmaid.entity.ai.bridge

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class MaidWsSessionTest {
    private class Maid(val id: UUID = UUID.randomUUID(), val world: Int = 1, var removed: Boolean = false)
    private val session = MaidWsSession<Maid>({ it.id }, { it.world == 1 && !it.removed })

    @Test fun firstHelloIncludesUntickedLiveMaid() {
        val maid = Maid()
        session.markOpened()
        session.seed(sequenceOf(maid))
        assertEquals(listOf(maid.id), session.members().map { it.id })
        assertFalse(session.handshaken)
    }

    @Test fun rosterExcludesRemovedAndForeignServerMaids() {
        val maid = Maid()
        val removed = Maid()
        session.observe(maid)
        session.observe(removed)
        removed.removed = true
        session.seed(sequenceOf(Maid(maid.id), Maid(world = 2)))
        assertEquals(listOf(maid.id), session.members().map { it.id })
    }

    @Test fun idlessSelectionWorksImmediatelyAfterAckForSingleMaid() {
        val maid = Maid()
        session.markOpened()
        session.seed(sequenceOf(maid))
        assertTrue(session.acknowledge())
        assertSame(maid, session.members().single())
    }

    @Test fun preAckDoesNotSendPerceptionAndPresenceDrainsAfterAck() {
        session.markOpened()
        session.enqueue("presence")
        assertFalse(session.handshaken)
        assertNull(session.pollOutgoing())
        assertTrue(session.acknowledge())
        assertFalse(session.acknowledge())
        assertEquals("presence", session.pollOutgoing())
        assertNull(session.pollOutgoing())
    }

    @Test fun restartDoesNotLeakPreviousWorldRoster() {
        val old = Maid()
        session.markOpened()
        session.acknowledge()
        session.observe(old)
        session.enqueue("old frame")
        assertEquals(listOf(old), session.members())
        session.endWorld()
        session.markOpened()
        session.acknowledge()
        assertTrue(session.members().isEmpty())
        assertNull(session.pollOutgoing())
    }

    @Test fun chatObservesTransportReloadBeforeAccepting() {
        session.markOpened()
        session.acknowledge()
        var reloads = 0
        assertFalse(session.queueChat({ reloads++; session.disconnect(); true }, { session.open }) { "stale" })
        assertEquals(1, reloads)
        assertNull(session.pollOutgoing())
        session.markOpened()
        session.acknowledge()
        assertNull(session.pollOutgoing())
    }

    @Test fun chatQueuesOnceAfterCurrentAck() {
        session.markOpened()
        assertFalse(session.queueChat({ true }, { true }) { "before ACK" })
        session.acknowledge()
        assertTrue(session.queueChat({ true }, { true }) { "current" })
        assertEquals("current", session.pollOutgoing())
        assertNull(session.pollOutgoing())
    }

    @Test fun invalidOrDisabledTransportDoesNotQueue() {
        session.markOpened()
        session.acknowledge()
        assertFalse(session.queueChat({ false }, { true }) { "disabled" })
        assertFalse(session.queueChat({ true }, { false }) { "socket absent" })
        assertNull(session.pollOutgoing())
    }
}
