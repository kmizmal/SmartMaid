package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.PauseReason
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.QueueKind
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.State
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag

/**
 * AI 任务调度器（M2；Q2 接入任务队列，Q5 战斗打断改为暂停保留，Q6 去重互斥）。
 *
 * 与现有 Goal 协作、战斗优先级、双队列、去重互斥语义与 Java 原版保持一致；
 * Java 侧可见 API（构造器 / public 方法 / static 常量 / EnqueueResult 字段）均保留。
 */
open class MaidTaskManager(private val maid: SmartMaidEntity?) {

    private val queue = MaidTaskQueue()
    /** 排队项 id → 运行时任务对象（挂起保留；持久化时改用 cmd+params 重建）。 */
    private val runtimeTasks = HashMap<String, MaidAITask>()
    /** 已经 start 过的项 id（恢复时避免重复 start，直接接着 tick）。 */
    private val startedIds = HashSet<String>()
    /** 时间窗去重：mutexKey → 最近一次入队的 tick（§8.4）。 */
    private val recentKeys = HashMap<String, Long>()

    private var ticks = 0
    private var maxTicks = MAX_TASK_TICKS
    /** 调度器自增计时（时间戳/时间窗去重用）。 */
    private var gameTick: Long = 0
    /** 即时任务（setTask 路径）的 id 序号。 */
    private var soloSeq: Long = 0

    /** 连续失败计数（每队列）：连续 2 项失败 → 放弃该队列（§十一）。 */
    private var shortFailStreak = 0
    private var longFailStreak = 0
    /** 无进展检测：上次进度令牌 + 未变化 tick 数。 */
    private var lastToken: String? = null
    private var noProgressTicks = 0

    /** 任务队列（只读访问 + 供菜单/协议读取快照）。 */
    open fun queue(): MaidTaskQueue = this.queue

    /** AI 任务是否正在接管行为决策（现有 Goal 据此让路）。队列非空也算忙，避免换项空档抖动。 */
    open fun isAiBusy(): Boolean = !this.queue.isEmpty()

    open fun currentTask(): MaidAITask? {
        val cur = this.queue.current() ?: return null
        return this.runtimeTasks[cur.id()]
    }

    /**
     * 设置/抢占当前任务（默认即时语义，保持不变）。
     * @return true 表示任务已开始；false 表示前置条件不满足
     */
    open fun setTask(task: MaidAITask?): Boolean {
        // 战斗高于任务：战斗期间拒绝新任务（cancel(null) 仍放行）
        if (task != null && this.maid?.isCombatActive() == true) {
            MaidDebug.log("任务被拒(战斗中): " + task.taskId())
            return false
        }
        cancelCurrent()
        if (task == null) {
            return true
        }
        val m = this.maid ?: return false
        if (!task.canStart(m)) {
            MaidDebug.log("任务被拒: " + task.taskId())
            return false
        }
        val id = "solo-" + (++this.soloSeq)
        val qt = QueuedTask(id, QueueKind.SHORT, task.taskId(), JsonObject())
        qt.enqueuedAtTick = this.gameTick
        this.queue.enqueue(qt, true)
        this.runtimeTasks[id] = task
        this.queue.startNext(this.gameTick)
        this.ticks = 0
        task.start(m)
        this.startedIds.add(id)
        MaidDebug.log("任务开始: " + task.taskId())
        return true
    }

    /** 入队一个任务（不带来源信息的便捷重载）。 */
    open fun enqueueTask(
        task: MaidAITask?, id: String, cmd: String, params: JsonObject,
        kind: QueueKind, front: Boolean, displayName: String?
    ): EnqueueResult = enqueueTask(task, id, cmd, params, kind, front, displayName, null, null, PRIORITY_DEFAULT)

    open fun enqueueTask(
        task: MaidAITask?, id: String, cmd: String, params: JsonObject,
        kind: QueueKind, front: Boolean, displayName: String?,
        groupId: String?, parentLabel: String?, priority: Int
    ): EnqueueResult {
        if (task == null) {
            return EnqueueResult.rejected("invalid", null, "任务为空")
        }
        val base: BlockPos? = this.maid?.blockPosition()
        val key = TaskMutex.key(cmd, params, base)

        // 1. guard 合并（唯一允许参数合并的 cmd，§8.8a）：不新增，range 取 max
        if ("guard:*" == key) {
            val existing = findByMutexKey("guard:*")
            if (existing != null) {
                val maxRange = Math.max(paramInt(existing.params(), "range", 16), paramInt(params, "range", 16))
                existing.params().addProperty("range", maxRange)
                val rt = this.runtimeTasks[existing.id()]
                if (rt is GuardTask) {
                    rt.setRange(maxRange)
                }
                val note = "已并入现有护卫任务，范围取最大 $maxRange"
                MaidDebug.log("入队判定: guard:* → MERGED(" + existing.id() + ") range=$maxRange")
                return EnqueueResult.mergedResult(existing.id(), note)
            }
        }

        // 2. 精确同键冲突（含来源优先级，§8.6）
        var replaced = false
        if (key != null) {
            val same = findByMutexKey(key)
            if (same != null) {
                if (priority == PRIORITY_OWNER && same.priority() > PRIORITY_OWNER) {
                    // 主人显式诉求压过桌宠自动识别：移除旧的 P3，接受新项（不受时间窗限制）
                    dropExisting(same)
                    replaced = true
                    MaidDebug.log("入队判定: $key → REPLACE(P2 over P3 ${same.id()})")
                } else {
                    MaidDebug.log("入队判定: $key → REJECT(同 ${same.id()})")
                    return EnqueueResult.rejected("duplicate", same.id(), "已有相同任务：$key")
                }
            }
            // 3. 时间窗去重（同键 60t 内，§8.4；P2 覆盖 P3 的情形除外）
            if (!replaced) {
                val last = this.recentKeys[key]
                if (last != null && this.gameTick - last <= DEDUP_WINDOW_TICKS) {
                    MaidDebug.log("入队判定: $key → REJECT(duplicate_recent)")
                    return EnqueueResult.rejected("duplicate_recent", null, "刚刚已下发相同任务")
                }
            }
        }

        // 4. 泛化/精确软提示（不拒，§8.3）
        val overlap = ArrayList<String>()
        if (key != null) {
            for (t in allQueued()) {
                if (TaskMutex.possibleOverlap(key, t.mutexKey())) {
                    overlap.add(t.id())
                }
            }
        }

        // 5. 入队
        val qt = QueuedTask(id, kind, cmd, params)
        qt.displayName = displayName
        qt.mutexKey = key
        qt.groupId = groupId
        qt.parentLabel = parentLabel
        qt.priority = priority
        qt.enqueuedAtTick = this.gameTick
        if (!this.queue.enqueue(qt, front)) {
            return EnqueueResult.rejected("queue_full", null, "队列已满（$kind）")
        }
        this.runtimeTasks[id] = task
        if (key != null) {
            this.recentKeys[key] = this.gameTick
            pruneRecent()
        }
        val note = if (overlap.isEmpty()) "已入队" else "队列已有更精确/更泛的同类任务"
        MaidDebug.log("入队判定: $key → ACCEPT" + if (overlap.isEmpty()) "" else "+overlap$overlap")
        return EnqueueResult.accepted(id, overlap, note)
    }

    /** 取消当前任务（指令 cancel / 坐下 / 主人取消）。队列其余项保留（§八）。 */
    open fun cancel() {
        cancelCurrent()
        this.ticks = 0
    }

    /** 停止指定项（长期「停止」= 永久移除，§4.3）；若该项正在执行则取消当前。 */
    open fun stopTask(id: String?): Boolean {
        if (id.isNullOrEmpty()) {
            return false
        }
        val cur = this.queue.current()
        if (cur != null && id == cur.id()) {
            cancelCurrent()
            return true
        }
        val removed = this.queue.stop(id)
        if (removed) {
            val rt = this.runtimeTasks.remove(id)
            this.startedIds.remove(id)
            rt?.forceStop(this.maid)
        }
        return removed
    }

    /** 暂停当前项（USER 原因）并回队首（协议 op:pauseCurrent）。 */
    open fun pauseCurrent(): Boolean {
        if (this.queue.current() == null) {
            return false
        }
        pauseCurrent(PauseReason.USER)
        return true
    }

    /** 调整任务在长期/短期队列之间（协议 op:setLongTerm）；仅对排队项有效。 */
    open fun setLongTerm(id: String?, longTerm: Boolean): Boolean {
        val taskId = id ?: return false
        val t = this.queue.findById(taskId) ?: return false
        val target = if (longTerm) QueueKind.LONG else QueueKind.SHORT
        if (t.queue() == target) {
            return true
        }
        val from = t.queue()
        this.queue.remove(from, taskId)
        t.queue = target
        if (!this.queue.enqueue(t, false)) {
            // 目标队列满 → 回退原队列
            t.queue = from
            this.queue.enqueue(t, false)
            return false
        }
        return true
    }

    /** 整组停止（协议 op:stopGroup，§8.5）：移除该 groupId 的全部项（含正在执行的）。 */
    open fun stopGroup(groupId: String?): Int {
        if (groupId.isNullOrEmpty()) {
            return 0
        }
        var n = 0
        val cur = this.queue.current()
        if (cur != null && groupId == cur.groupId()) {
            cancelCurrent()
            n++
        }
        for (t in this.queue.shortItems()) {
            if (groupId == t.groupId()) {
                this.runtimeTasks.remove(t.id())
                this.startedIds.remove(t.id())
                this.queue.remove(QueueKind.SHORT, t.id())
                n++
            }
        }
        for (t in this.queue.longItems()) {
            if (groupId == t.groupId()) {
                this.runtimeTasks.remove(t.id())
                this.startedIds.remove(t.id())
                this.queue.remove(QueueKind.LONG, t.id())
                n++
            }
        }
        return n
    }

    /** 记录一次失败：连续 2 项失败 → 放弃该队列（§十一）。 */
    private fun noteFail(kind: QueueKind) {
        if (kind == QueueKind.LONG) {
            this.longFailStreak++
            if (this.longFailStreak >= 2) {
                MaidDebug.log("长期队列连续失败，放弃")
                this.queue.clear(QueueKind.LONG)
                this.longFailStreak = 0
            }
        } else {
            this.shortFailStreak++
            if (this.shortFailStreak >= 2) {
                MaidDebug.log("短期队列连续失败，放弃")
                this.queue.clear(QueueKind.SHORT)
                this.shortFailStreak = 0
            }
        }
    }

    private fun noteSuccess(kind: QueueKind) {
        if (kind == QueueKind.LONG) {
            this.longFailStreak = 0
        } else {
            this.shortFailStreak = 0
        }
    }

    /** 只停当前项（清运行时对象 + 标记 CANCELLED），不影响排队项。 */
    private fun cancelCurrent() {
        val cur = this.queue.current() ?: return
        MaidDebug.log("任务取消: " + cur.cmd())
        val task = this.runtimeTasks.remove(cur.id())
        this.startedIds.remove(cur.id())
        task?.forceStop(this.maid)
        this.queue.cancelCurrent()
    }

    /** 暂停当前项并回队首（对象保留，不 forceStop、不丢引用）—— 战斗打断用（§五.2 / §六.1）。 */
    private fun pauseCurrent(reason: PauseReason) {
        val cur = this.queue.current() ?: return
        val task = this.runtimeTasks[cur.id()]
        if (task != null) {
            task.onSuspend() // 释放外部资源（如熔炉租约），恢复时重新获取
            if (task is Resumable) {
                cur.progress = task.saveState() // 参数级快照（§6.2，供持久化/回溯）
            }
        }
        MaidDebug.log("任务暂停($reason): " + cur.cmd())
        this.ticks = 0
        this.queue.pauseCurrent(reason, false)
    }

    /** 资源阻塞：暂停 + 移到本队列队尾（不占队头，让能跑的项先跑，§8.8b）。 */
    private fun blockCurrent() {
        val cur = this.queue.current() ?: return
        val kind = cur.queue()
        pauseCurrent(PauseReason.BLOCKED)
        val last = (if (kind == QueueKind.LONG) this.queue.longSize() else this.queue.shortSize()) - 1
        if (last > 0) {
            this.queue.move(kind, cur.id(), last)
        }
        MaidDebug.log("任务阻塞让位到队尾: " + cur.cmd())
    }

    /** 每 tick 驱动（SmartMaidEntity.aiStep 服务端分支调用）。 */
    open fun tick() {
        this.gameTick++

        // 战斗高于任务：当前项暂停 + 保留对象并回队首，战斗结束自动继续（§五.2）
        if (this.maid?.isCombatActive() == true) {
            pauseCurrent(PauseReason.COMBAT)
            return
        }
        // 坐下 / 死亡 → 强制终止当前（不启动新任务；保持旧行为）
        val m = this.maid
        if (m != null && (m.isOrderedToSit() || !m.isAlive)) {
            cancelCurrent()
            return
        }

        // 无当前项 → 启动队列下一项（短期优先，空则长期；已暂停项在此恢复）
        if (this.queue.current() == null) {
            startNextLoop()
        }
        val cur = this.queue.current() ?: return
        val task = this.runtimeTasks[cur.id()]
        if (task == null) {
            MaidDebug.log("运行时任务缺失，跳过: " + cur.cmd())
            this.queue.finishCurrent(State.FAILED, "运行时任务缺失")
            return
        }

        this.ticks++
        val maid = this.maid ?: return
        task.tick(maid)
        // 资源阻塞（如无空闲熔炉）：暂停 + 让出队头，等资源空出再恢复（§8.8b）
        if (task.isBlocked() && !task.isDone()) {
            blockCurrent()
            return
        }
        // 长期任务无进展检测（§十一）：仅对提供进度令牌的任务生效（guard 等不提供 → 不误判）
        val token = task.progressToken()
        if (token != null) {
            if (token != this.lastToken) {
                this.lastToken = token
                this.noProgressTicks = 0
            } else {
                this.noProgressTicks++
                if (this.noProgressTicks > NO_PROGRESS_LIMIT) {
                    MaidDebug.log("长任务无进展，暂停: " + task.taskId())
                    this.noProgressTicks = 0
                    pauseCurrent(PauseReason.BLOCKED)
                    return
                }
            }
        }
        val timedOut = !task.isContinuous() && this.ticks > this.maxTicks
        if (task.isDone() || timedOut) {
            val result = if (timedOut) "超时" else task.result()
            val end = if (timedOut) State.FAILED else State.DONE
            MaidDebug.log("任务结束: " + task.taskId() + " result=$result ticks=" + this.ticks)
            // try/finally 保证推进一定可达（§八：否则"当前项没了、队列还有、站着不动"）
            try {
                task.forceStop(this.maid)
                this.queue.finishCurrent(end, result)
                if (end == State.FAILED) {
                    noteFail(cur.queue())
                } else {
                    noteSuccess(cur.queue())
                }
            } finally {
                this.runtimeTasks.remove(cur.id())
                this.startedIds.remove(cur.id())
                this.ticks = 0
                startNextLoop()
            }
        }
    }

    /**
     * 循环启动下一个可开始的排队项；跳过 canStart 失败的新项。
     * 已 start 过（被暂停）的项直接恢复，不重复 start（§六.1 零成本接续）。
     */
    private fun startNextLoop() {
        while (this.queue.current() == null) {
            val next = this.queue.peekNext() ?: return
            this.queue.startNext(this.gameTick)
            val task = this.runtimeTasks[next.id()]
            if (task == null) {
                MaidDebug.log("运行时任务缺失，跳过: " + next.cmd())
                this.queue.finishCurrent(State.SKIPPED, "运行时任务缺失")
                noteFail(next.queue())
                continue
            }
            val resumed = this.startedIds.contains(next.id())
            if (!resumed) {
                next.runAttempts = next.runAttempts() + 1
                // 不可接续任务重跑超限 → 跳过（§十一）
                if (task !is Resumable && next.runAttempts() > MAX_ATTEMPTS) {
                    MaidDebug.log("重跑超限，跳过: " + next.cmd())
                    this.runtimeTasks.remove(next.id())
                    this.queue.finishCurrent(State.SKIPPED, "重跑超限")
                    noteFail(next.queue())
                    continue
                }
                val m = this.maid ?: return
                if (!task.canStart(m)) {
                    MaidDebug.log("跳过无法开始的任务: " + next.cmd())
                    this.runtimeTasks.remove(next.id())
                    this.queue.finishCurrent(State.SKIPPED, "前置条件不满足")
                    noteFail(next.queue())
                    continue
                }
                this.lastToken = null
                this.noProgressTicks = 0
                this.ticks = 0
                task.start(m)
                this.startedIds.add(next.id())
                // 跨会话回溯：有快照且环境校验通过 → 灌回中间状态（§6.2 回溯为默认、校验为兜底）
                val progress = next.progress()
                if (progress != null && task is Resumable && task.validateState(m, progress)) {
                    task.restoreState(progress)
                }
                MaidDebug.log("任务开始: " + task.taskId() + " (" + next.queue() + ")")
            } else {
                // 恢复：对象完整，直接接着 tick（挂起期间未销毁）
                task.onResume()
                this.ticks = 0
                MaidDebug.log("任务恢复: " + task.taskId() + " (" + next.queue() + ")")
            }
            return
        }
    }

    // ---------- 去重辅助（Q6） ----------

    /** 当前项 + 两个队列的全部项。 */
    private fun allQueued(): List<QueuedTask> {
        val all = ArrayList<QueuedTask>()
        this.queue.current()?.let { all.add(it) }
        all.addAll(this.queue.shortItems())
        all.addAll(this.queue.longItems())
        return all
    }

    private fun findByMutexKey(key: String?): QueuedTask? {
        if (key == null) {
            return null
        }
        for (t in allQueued()) {
            if (key == t.mutexKey()) {
                return t
            }
        }
        return null
    }

    /** 移除一个已存在的项（可能是 current）：清运行时对象并 forceStop。 */
    private fun dropExisting(task: QueuedTask) {
        if (task === this.queue.current()) {
            cancelCurrent()
            return
        }
        val rt = this.runtimeTasks.remove(task.id())
        this.startedIds.remove(task.id())
        rt?.forceStop(this.maid)
        this.queue.remove(task.queue(), task.id())
    }

    private fun pruneRecent() {
        if (this.recentKeys.size <= 256) {
            return
        }
        val cutoff = this.gameTick - DEDUP_WINDOW_TICKS * 4
        this.recentKeys.entries.removeIf { e -> e.value < cutoff }
    }

    // ---------- 持久化（Q8） ----------

    /** 把两个队列（含正在执行的项）写入 NBT tasks 段（§7.1）。 */
    open fun saveToTag(tasks: CompoundTag) {
        tasks.putInt("version", TASKS_VERSION)
        tasks.put("longTerm", saveList(QueueKind.LONG))
        tasks.put("shortTerm", saveList(QueueKind.SHORT))
        tasks.putLong("lastTick", this.gameTick)
    }

    private fun saveList(kind: QueueKind): ListTag {
        val list = ListTag()
        val cur = this.queue.current()
        if (cur != null && cur.queue() == kind) {
            list.add(taskTag(cur))
        }
        val items = if (kind == QueueKind.LONG) this.queue.longItems() else this.queue.shortItems()
        for (t in items) {
            list.add(taskTag(t))
        }
        return list
    }

    /**
     * 从 NBT 恢复两个队列（§7.1）。旧存档无 tasks 段 → 视为空队列（不报错、不迁移）。
     * 恢复后调度器会在后续 tick 自动继续（短期优先），并对有快照的项做 restoreState。
     * @return 恢复的任务数
     */
    open fun loadFromTag(tasks: CompoundTag?, factory: TaskFactory): Int {
        this.queue.clearAll()
        this.runtimeTasks.clear()
        this.startedIds.clear()
        this.recentKeys.clear()
        if (tasks == null || tasks.getIntOr("version", 0) <= 0) {
            return 0
        }
        var n = loadList(tasks.getListOrEmpty("longTerm"), QueueKind.LONG, factory)
        n += loadList(tasks.getListOrEmpty("shortTerm"), QueueKind.SHORT, factory)
        MaidDebug.log("恢复任务队列: $n 项")
        return n
    }

    private fun loadList(list: ListTag, kind: QueueKind, factory: TaskFactory): Int {
        var n = 0
        for (i in 0 until list.size) {
            if (addLoaded(list.getCompoundOrEmpty(i), kind, factory)) {
                n++
            }
        }
        return n
    }

    private fun addLoaded(it: CompoundTag, kind: QueueKind, factory: TaskFactory): Boolean {
        val cmd = it.getStringOr("cmd", "")
        val params = parseJson(it.getStringOr("params", "{}"))
        val task = factory.create(this.maid, cmd, params, StringBuilder())
        if (task == null) {
            MaidDebug.log("恢复任务失败（无法重建）: $cmd")
            return false
        }
        val id = it.getStringOr("id", "restored-" + (++this.soloSeq))
        val qt = QueuedTask(id, kind, cmd, params)
        qt.displayName = it.getStringOr("name", cmd)
        qt.mutexKey = it.getStringOr("mutexKey", "")
        qt.groupId = it.getStringOr("group", "")
        qt.priority = it.getIntOr("priority", PRIORITY_DEFAULT)
        qt.interruptCount = it.getIntOr("interrupts", 0)
        qt.runAttempts = it.getIntOr("attempts", 0)
        val prog = it.getStringOr("progress", "")
        if (prog.isNotEmpty()) {
            qt.progress = parseJson(prog)
        }
        if (!this.queue.enqueue(qt, false)) {
            return false
        }
        this.runtimeTasks[id] = task
        return true
    }

    // ---------- 快照 ----------

    /** 队列快照（菜单任务页 / queue op:query 用；DESIGN_MAID_TASK_QUEUE §十）。 */
    open fun snapshot(): JsonObject {
        val o = JsonObject()
        val cur = this.queue.current()
        if (cur != null) {
            o.add("current", taskJson(cur))
        } else {
            o.add("current", JsonNull.INSTANCE)
        }
        o.addProperty("short_size", this.queue.shortSize())
        o.addProperty("long_size", this.queue.longSize())
        o.addProperty("combat", this.maid?.isCombatActive() == true)
        val shortArr = JsonArray()
        for (t in this.queue.shortItems()) {
            shortArr.add(taskJson(t))
        }
        val longArr = JsonArray()
        for (t in this.queue.longItems()) {
            longArr.add(taskJson(t))
        }
        o.add("short", shortArr)
        o.add("long", longArr)
        return o
    }

    /** 任务工厂：从指令 + 参数重建任务对象（由 bridge 层提供，避免 maidtask→bridge 依赖）。 */
    fun interface TaskFactory {
        fun create(maid: SmartMaidEntity?, cmd: String?, params: JsonObject?, err: StringBuilder?): MaidAITask?
    }

    /** enqueueTask 的判定结果（供协议/菜单构造回执）；Kotlin 嵌套类即 Java static nested class。 */
    class EnqueueResult(
        @JvmField val ok: Boolean,
        @JvmField val reason: String?,
        @JvmField val existingId: String?,
        @JvmField val merged: Boolean,
        @JvmField val note: String?,
        @JvmField val overlap: List<String>
    ) {
        companion object {
            @JvmStatic
            fun accepted(id: String, overlap: List<String>, note: String?): EnqueueResult =
                EnqueueResult(true, null, id, false, note, overlap)

            @JvmStatic
            fun mergedResult(existingId: String, note: String): EnqueueResult =
                EnqueueResult(true, null, existingId, true, note, emptyList())

            @JvmStatic
            fun rejected(reason: String, existingId: String?, note: String?): EnqueueResult =
                EnqueueResult(false, reason, existingId, false, note, emptyList())
        }
    }

    companion object {
        /** 任务最长运行 tick（60 秒兜底，防任务卡死） */
        private const val MAX_TASK_TICKS = 20 * 60
        /** 时间窗去重：同 mutexKey 在 60 tick（3 秒）内重复下发只接受第一条（§8.4） */
        private const val DEDUP_WINDOW_TICKS = 60L
        /** 来源优先级：主人显式（P2） */
        const val PRIORITY_OWNER = 2
        /** 来源优先级：桌宠事件线 / 脚本 / AutoTest（P3） */
        const val PRIORITY_DEFAULT = 3

        /** 不可接续任务最大重跑次数（§十一）。 */
        private const val MAX_ATTEMPTS = 3
        /** 长任务无进展阈值（3 分钟，仅对提供进度令牌的任务生效）。 */
        private const val NO_PROGRESS_LIMIT = 20 * 60 * 3

        /** 任务快照 NBT 版本（字段增删时兼容；旧段 version 缺失视为无队列）。 */
        const val TASKS_VERSION = 1

        private fun paramInt(params: JsonObject, key: String, def: Int): Int {
            val element: JsonElement? = if (params.has(key)) params[key] else null
            return if (element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber)
                element.asInt else def
        }

        private fun parseJson(s: String): JsonObject = try {
            JsonParser.parseString(s).asJsonObject
        } catch (e: Exception) {
            JsonObject()
        }

        private fun taskTag(t: QueuedTask): CompoundTag {
            val it = CompoundTag()
            it.putString("id", t.id())
            it.putString("cmd", t.cmd())
            it.putString("params", t.params().toString())
            val displayName = t.displayName()
            if (displayName != null) {
                it.putString("name", displayName)
            }
            val mutexKey = t.mutexKey()
            if (mutexKey != null) {
                it.putString("mutexKey", mutexKey)
            }
            val groupId = t.groupId()
            if (groupId != null) {
                it.putString("group", groupId)
            }
            val parentLabel = t.parentLabel()
            if (parentLabel != null) {
                it.putString("parentLabel", parentLabel)
            }
            it.putInt("priority", t.priority())
            it.putInt("interrupts", t.interruptCount())
            it.putInt("attempts", t.runAttempts())
            val progress = t.progress()
            if (progress != null) {
                it.putString("progress", progress.toString())
            }
            return it
        }

        private fun taskJson(t: QueuedTask): JsonObject {
            val o = JsonObject()
            o.addProperty("id", t.id())
            o.addProperty("cmd", t.cmd())
            o.addProperty("state", t.state().name)
            o.addProperty("seq", t.seq())
            o.addProperty("queue", t.queue().name)
            val mutexKey = t.mutexKey()
            if (mutexKey != null) {
                o.addProperty("mutexKey", mutexKey)
            }
            val groupId = t.groupId()
            if (groupId != null) {
                o.addProperty("group", groupId)
            }
            val parentLabel = t.parentLabel()
            if (parentLabel != null) {
                o.addProperty("groupLabel", parentLabel)
            }
            val displayName = t.displayName()
            if (displayName != null) {
                o.addProperty("name", displayName)
            }
            val pauseReason = t.pauseReason()
            if (pauseReason != null) {
                o.addProperty("pauseReason", pauseReason.name)
            }
            o.addProperty("interrupts", t.interruptCount())
            return o
        }
    }
}
