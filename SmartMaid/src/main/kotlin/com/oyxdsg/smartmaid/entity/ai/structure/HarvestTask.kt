package com.oyxdsg.smartmaid.entity.ai.structure

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidBlockBreaker
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask
import com.oyxdsg.smartmaid.entity.ai.maidtask.Resumable
import com.oyxdsg.smartmaid.entity.ai.maidtask.TaskState
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import java.util.ArrayDeque
import java.util.HashMap
import java.util.HashSet
import java.util.function.Predicate

/**
 * 收割任务（Atomic Command Protocol §5.4）：对某结构（树/矿脉…），把种子位置所在的那一团
 * **相连资源**采完（树 = 一棵完整的树、矿 = 一个完整的矿簇），到达 count 或该结构耗尽结束。
 * BFS 从种子位置沿 [core]（资源本身）扩展 —— 判据是"相连的资源"，**不看树种/矿种**：
 * 深色橡木、白桦、橡木同样是树。produce 只用于 find 阶段挑哪一棵树。
 */
open class HarvestTask(
    private val seed: BlockPos?,
    private val matcher: Predicate<BlockState>?,
    private val count: Int,
    private val structure: StructureType
) : MaidAITask("harvest"), Resumable {

    private val breaker = MaidBlockBreaker()
    private val pending = ArrayDeque<BlockPos>()
    private val visited = HashSet<Long>()
    /** 被遮挡重试次数：被挡放回队列末尾重试，超限放弃（防死循环） */
    private val retryCount = HashMap<BlockPos, Int>()
    private var collected = 0
    private var remaining = 0
    private var scanned = false
    private var done = false
    private var lastOk = true

    companion object {
        /** BFS 收集目标方块的硬上限（防超大组件一次扫太多，性能护栏） */
        private const val SCAN_TARGET_LIMIT = 2000

        /** 起点容错半径：seed 已被挖掉/不匹配时，在这个半径内找最近的资源方块当起点 */
        private const val SEED_RECOVER_RADIUS = 2

        private const val MAX_RETRY = 4
    }

    override fun canStart(maid: SmartMaidEntity): Boolean {
        return this.seed != null && this.matcher != null
    }

    override fun start(maid: SmartMaidEntity) {
        this.collected = 0
        this.remaining = 0
        this.scanned = false
        this.done = false
        this.lastOk = true
        this.retryCount.clear()
        MaidDebug.log("Harvest start seed=" + seed + " count=" + count)
    }

    override fun tick(maid: SmartMaidEntity) {
        if (this.done) {
            return
        }
        if (this.breaker.isActive()) {
            val currentTarget: BlockPos? = this.breaker.target()
            this.lastOk = this.breaker.tick(maid)
            if (this.lastOk) {
                this.collected++
                if (this.remaining > 0) {
                    this.remaining--
                }
                MaidDebug.log(
                    "Harvest 收割 " + this.collected + " 块（目标 "
                            + (if (this.count > 0) this.count.toString() else "产物耗尽") + "）"
                )
            } else if (!this.breaker.isActive() && currentTarget != null
                && !maid.level().getBlockState(currentTarget).isAir()
            ) {
                // 目标被遮挡/不可达 → 放回队列末尾（从外到内逐步收割，对齐玩家砍树行为），
                // 等外围方块挖掉后它变为可达再收割；超限重试则放弃（防死循环）
                // 等价Integer::sum 的 merge 语义：不存在则记 1，存在则 +1（避免 SAM 类型推断风险）
                val n = (this.retryCount[currentTarget] ?: 0) + 1
                this.retryCount[currentTarget] = n
                if (n >= MAX_RETRY) {
                    if (MaidDebug.verbose()) {
                        MaidDebug.log("Harvest 目标多次被挡，放弃 " + currentTarget)
                    }
                } else {
                    if (MaidDebug.verbose()) {
                        MaidDebug.log("Harvest 目标被挡，放回队列 " + currentTarget)
                    }
                    this.pending.addLast(currentTarget)
                }
            }
            return
        }
        if (!this.scanned) {
            scan(maid)
            this.scanned = true
            if (this.pending.isEmpty()) {
                MaidDebug.log("Harvest 结束（无目标可收）collected=" + this.collected)
                this.done = true
                return
            }
        }
        if (this.count > 0 && this.collected >= this.count) {
            MaidDebug.log(
                "Harvest 结束（达成目标）collected=" + this.collected + "/" + this.count
            )
            this.done = true
            return
        }
        // 取下一个可挖目标：目标池在 scan() 里已经确定为"这棵相连的资源"，这里只跳过已空的
        // （**不能再用产物谓词过滤** —— 否则深色橡木这类"同为 tree 的 core、但不匹配 produce"的
        //  方块会被全部跳过，表现为"扫到 47 个目标却 0 个可挖"）
        while (!this.pending.isEmpty()) {
            val p: BlockPos = this.pending.poll()
            val s = maid.level().getBlockState(p)
            if (s.isAir()) {
                continue
            }
            // 重试超限的目标直接放弃（多次被挡，玩家也挖不到）
            if (this.retryCount.getOrDefault(p, 0) >= MAX_RETRY) {
                continue
            }
            if (this.breaker.begin(maid, p)) {
                return
            }
            if (MaidDebug.verbose()) {
                MaidDebug.log("Harvest 跳过不可挖: " + p)
            }
        }
        MaidDebug.log("Harvest 结束（无剩余目标）collected=" + this.collected)
        this.done = true
    }

    /**
     * BFS 从 seed 沿**相连的资源（core）**扩展，收集目标方块 —— 即"一棵完整的树 / 一个完整的矿簇"。
     *
     * 判据分层（2026-09-29 定稿）：连通性 = core（资源本身，如 #minecraft:logs）；范围 = 相连的
     * core（原木彼此相连、不同树只靠树叶相接，故沿 core 扩展天然锁定一棵树）；produce 不参与
     * "哪些方块算目标"（只在 find 阶段决定挑哪一棵）。
     *
     * 起点容错：seed 可能已经不是资源方块（脚本常是 find → move → harvest，move 用同一坐标），
     * 此时在 [SEED_RECOVER_RADIUS] 半径内找最近的 core 当起点。
     */
    private fun scan(maid: SmartMaidEntity) {
        val level: Level = maid.level()
        val seedPos = this.seed ?: return
        val queue = ArrayDeque<BlockPos>()
        queue.add(seedPos)
        this.visited.add(seedPos.asLong())
        if (!this.structure.coreBlock().test(level.getBlockState(seedPos))) {
            val alt = nearestCore(level, seedPos, SEED_RECOVER_RADIUS)
            MaidDebug.log(
                "Harvest seed " + seedPos + " 已非资源（实际 "
                        + BuiltInRegistries.BLOCK.getKey(level.getBlockState(seedPos).block)
                        + "），起点容错 -> " + alt
            )
            if (alt != null) {
                queue.add(alt)
                this.visited.add(alt.asLong())
            }
        }
        while (!queue.isEmpty() && this.remaining < SCAN_TARGET_LIMIT) {
            val p: BlockPos = queue.poll()
            val s = level.getBlockState(p)
            if (this.structure.coreBlock().test(s)) {
                this.pending.add(p)
                this.remaining++
            }
            for (d in Direction.values()) {
                val q = p.relative(d)
                if (!this.visited.add(q.asLong())) {
                    continue
                }
                // 只沿资源本身（core）扩展：一棵树 = 相连的原木（不分树种）；support（树叶）会串遍整片林子
                if (this.structure.coreBlock().test(level.getBlockState(q))) {
                    queue.add(q)
                }
            }
        }
        MaidDebug.log(
            "Harvest scan: 目标 " + this.remaining + " 个（seed " + seedPos
                    + " 实际 " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(seedPos).block)
                    + "，产物命中=" + (this.matcher?.test(level.getBlockState(seedPos)) ?: false) + "）"
        )
    }

    /** 在以 center 为中心、半径 radius 的立方体内找最近的 core 方块（不含 center 自身） */
    private fun nearestCore(level: Level, center: BlockPos, radius: Int): BlockPos? {
        var best: BlockPos? = null
        var bestDist = Double.MAX_VALUE
        for (p in BlockPos.betweenClosed(
            center.offset(-radius, -radius, -radius),
            center.offset(radius, radius, radius)
        )) {
            if (p == center || !this.structure.coreBlock().test(level.getBlockState(p))) {
                continue
            }
            val d = p.distSqr(center)
            if (d < bestDist) {
                bestDist = d
                best = p.immutable() // betweenClosed 返回可变游标，必须拷贝
            }
        }
        return best
    }

    // ---------- Resumable（Q7） ----------

    override fun saveState(): JsonObject {
        val o = JsonObject()
        o.addProperty("version", stateVersion())
        o.addProperty("collected", this.collected)
        o.addProperty("remaining", this.remaining)
        o.addProperty("scanned", this.scanned)
        o.addProperty("done", this.done)
        o.addProperty("lastOk", this.lastOk)
        o.add("pending", TaskState.posList(this.pending))
        val visitedArr = JsonArray()
        for (l in this.visited) {
            visitedArr.add(l)
        }
        o.add("visited", visitedArr)
        val retryArr = JsonArray()
        for (e in this.retryCount.entries) {
            val r = JsonObject()
            r.add("pos", TaskState.pos(e.key))
            r.addProperty("n", e.value)
            retryArr.add(r)
        }
        o.add("retry", retryArr)
        return o
    }

    override fun restoreState(s: JsonObject?) {
        if (s == null) return
        this.collected = if (s.has("collected")) {
            s.get("collected").asInt
        } else {
            0
        }
        this.remaining = if (s.has("remaining")) {
            s.get("remaining").asInt
        } else {
            0
        }
        this.scanned = s.has("scanned") && s.get("scanned").asBoolean
        this.done = s.has("done") && s.get("done").asBoolean
        this.lastOk = !s.has("lastOk") || s.get("lastOk").asBoolean
        this.pending.clear()
        this.pending.addAll(TaskState.readPosList(s.get("pending")))
        this.visited.clear()
        if (s.has("visited") && s.get("visited").isJsonArray) {
            for (e: JsonElement in s.getAsJsonArray("visited")) {
                this.visited.add(e.asLong)
            }
        }
        this.retryCount.clear()
        if (s.has("retry") && s.get("retry").isJsonArray) {
            for (e: JsonElement in s.getAsJsonArray("retry")) {
                if (e.isJsonObject) {
                    val r = e.asJsonObject
                    val p = TaskState.readPos(r.get("pos"))
                    if (p != null) {
                        this.retryCount[p] = if (r.has("n")) {
                            r.get("n").asInt
                        } else {
                            0
                        }
                    }
                }
            }
        }
    }

    override fun stateVersion(): Int = 1

    override fun validateState(maid: SmartMaidEntity?, s: JsonObject?): Boolean {
        return this.seed != null && this.matcher != null && maid != null
    }

    override fun isDone(): Boolean {
        return this.done
    }

    override fun result(): String {
        return if (this.done) {
            "已收割 " + this.collected + " 个"
        } else {
            "收割中"
        }
    }

    override fun forceStop(maid: SmartMaidEntity?) {
        if (maid != null) {
            this.breaker.abort(maid)
        }
        this.done = true
    }

    override fun progressToken(): String {
        return "collected=" + this.collected
    }

    override fun resultJson(): JsonObject {
        val out = JsonObject()
        out.addProperty("collected", this.collected)
        out.addProperty("remaining", Math.max(0, this.remaining))
        out.add("pos", StructureScan.posArray(this.seed!!))
        // 未收到任何目标时说明原因，便于 AI 决策（换位置重试 / 放弃）
        if (this.scanned && this.collected == 0) {
            out.addProperty("reason", "no_target")
        }
        return out
    }
}
