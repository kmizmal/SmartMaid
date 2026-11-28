package com.oyxdsg.smartmaid.entity.ai.bridge

import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** Server-thread state shared by the wire client and its deterministic tests. */
internal class MaidWsSession<M : Any>(private val id: (M) -> UUID, private val isLive: (M) -> Boolean) {
    private val roster = WeakHashMap<M, Unit>()
    private val outbox = ConcurrentLinkedQueue<String>()

    @Volatile var open = false
        private set
    @Volatile var handshaken = false
        private set

    fun observe(maid: M) {
        if (isLive(maid)) roster[maid] = Unit
    }

    fun forget(maid: M) { roster.remove(maid) }

    fun seed(maids: Sequence<M>) = maids.forEach(::observe)

    fun members(): List<M> {
        roster.keys.removeIf { !isLive(it) }
        return roster.keys.distinctBy(id)
    }

    fun markOpened() {
        open = true
        handshaken = false
        outbox.clear()
    }

    fun acknowledge(): Boolean {
        if (!open || handshaken) return false
        handshaken = true
        return true
    }

    fun disconnect() {
        open = false
        handshaken = false
        outbox.clear()
    }

    fun endWorld() {
        disconnect()
        roster.clear()
    }

    fun enqueue(frame: String): Boolean = outbox.size < 512 && outbox.offer(frame)

    fun pollOutgoing(): String? = if (handshaken) outbox.poll() else null

    fun queueChat(refresh: () -> Boolean, hasSocket: () -> Boolean, frame: () -> String): Boolean {
        if (!refresh() || !open || !handshaken || !hasSocket()) return false
        return enqueue(frame())
    }
}
