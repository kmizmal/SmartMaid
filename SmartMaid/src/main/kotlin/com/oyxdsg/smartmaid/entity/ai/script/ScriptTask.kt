package com.oyxdsg.smartmaid.entity.ai.script

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidAIBridge
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask
import com.oyxdsg.smartmaid.entity.ai.structure.HarvestTask
import com.oyxdsg.smartmaid.entity.ai.structure.OreStructure
import com.oyxdsg.smartmaid.entity.ai.structure.ProduceFilter
import com.oyxdsg.smartmaid.entity.ai.structure.StructureRegistry
import com.oyxdsg.smartmaid.entity.ai.structure.StructureScan
import com.oyxdsg.smartmaid.entity.ai.structure.StructureType
import net.minecraft.core.BlockPos
import java.math.BigDecimal
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Collections
import java.util.Deque
import java.util.function.Predicate

/**
 * 脚本任务（Atomic Command Protocol §3）：AI 一次性规划的指令流执行器。
 *
 * <p>控制原语：{@code as}（变量绑定，挂在任意指令上）/ {@code $var.path}（变量引用）/
 * {@code if}（条件分支）/ {@code loop}（条件循环 + max_iter）/ {@code assign}（整数算术）/
 * {@code terminate}（提前终止 + 回执）。子任务复用 {@link MaidAIBridge#buildTask}，
 * 查询原子与 harvest 在本引擎内特殊构造。</p>
 *
 * <p>护栏：max_steps（实际执行步，含分支/循环展开）、每步由 MaidTaskManager 的 60s
 * 超时兜底、禁止嵌套 script、战斗/坐下/取消由 MaidTaskManager 统一打断。</p>
 */
open class ScriptTask(script: JsonObject) : MaidAITask("script") {

    private val ctx = ScriptContext()
    private val steps: List<JsonObject>
    private val failPolicy: Int = if (script.has("fail") && "continue" == script.get("fail").asString) {
        FAIL_CONTINUE
    } else {
        FAIL_ABORT
    }
    private val maxSteps: Int
    private val reportEach: Boolean

    private val stack: Deque<Frame> = ArrayDeque()
    private var current: MaidAITask? = null
    // 非 null：current != null 时必已在 runStep 赋值为具体 cmd（字符串拼接语义不受影响）
    private var currentCmd: String = ""
    private var currentAs: String? = null
    private var lastSubError: String? = null
    private var done = false
    private var cancelled = false
    private var failInfo: JsonObject? = null
    private var totalTicks = 0
    private var stepStartTick = 0

    init {
        this.maxSteps = boundedInt(script, "max_steps", 64, MAX_STEPS_LIMIT)
        this.reportEach = script.has("report_each") && script.get("report_each").asBoolean
        this.ctx.scriptName = if (script.has("name")) script.get("name").asString else "script"
        if (script.has("vars") && script.get("vars").isJsonObject) {
            for ((key, value) in script.getAsJsonObject("vars").entrySet()) {
                this.ctx.vars[key] = value.deepCopy()
            }
        }
        if (!this.ctx.vars.containsKey("target_count")) {
            this.ctx.vars["target_count"] = JsonPrimitive(0)
        }
        val arr: JsonArray = if (script.has("steps") && script.get("steps").isJsonArray) {
            script.getAsJsonArray("steps")
        } else {
            JsonArray()
        }
        this.steps = jsonArrayToList(arr)
    }

    override fun canStart(maid: SmartMaidEntity): Boolean = this.steps.isNotEmpty()

    override fun start(maid: SmartMaidEntity) {
        // 引擎自动变量：$maid.pos（脚本开始时女仆坐标，"回来"锚点）
        val p: BlockPos = maid.blockPosition()
        val maidVar = JsonObject()
        maidVar.add("pos", StructureScan.posArray(p))
        this.ctx.vars["maid"] = maidVar

        this.stack.clear()
        this.stack.push(Frame(this.steps))
        MaidDebug.log("Script start: " + this.ctx.scriptName + " steps=" + this.steps.size)
    }

    override fun tick(maid: SmartMaidEntity) {
        if (this.done || this.cancelled) return
        // 整个脚本总超时护栏（10 分钟；MaidTaskManager 不对 continuous 任务设 60s 上限）
        this.totalTicks++
        if (this.totalTicks > MAX_SCRIPT_TICKS) {
            val rec = JsonObject()
            rec.addProperty("cmd", "#script_timeout")
            rec.addProperty("ok", false)
            rec.addProperty("error", "脚本执行超时")
            this.failInfo = rec
            this.terminate("脚本执行超时", rec)
            this.done = true
            return
        }
        this.current?.let { cur ->
            cur.tick(maid)
            // 单步子任务超时（60s）：move/attack 等卡死时强制结束该步
            if (!cur.isDone() && maid.tickCount - this.stepStartTick > MAX_STEP_TICKS) {
                MaidDebug.log("Script 步超时: " + this.currentCmd + " → 强制结束")
                cur.forceStop(maid)
                val rec = JsonObject()
                rec.addProperty("cmd", this.currentCmd)
                rec.addProperty("ok", false)
                rec.addProperty("error", "步骤执行超时")
                this.ctx.logStep(this.currentCmd, false, rec)
                if (this.failPolicy == FAIL_ABORT) {
                    this.failInfo = rec
                    this.terminate("步骤超时: " + this.currentCmd, rec)
                    this.done = true
                    return
                }
                this.current = null
                return
            }
            if (cur.isDone()) {
                this.recordStep()
                this.current = null
            }
            return
        }
        this.advance(maid)
    }

    /** 从执行栈取下一步并推进（含控制原语就地处理 / 子任务构造） */
    private fun advance(maid: SmartMaidEntity) {
        while (!this.done && !this.cancelled) {
            if (this.ctx.terminated) {
                this.done = true
                return
            }
            if (this.stack.isEmpty()) {
                this.done = true
                return
            }
            val f = this.stack.peek()
            if (f.isLoop) {
                if (f.iterLeft <= 0 || (f.whileCond != null && !ScriptRef.evalCond(f.whileCond, this.ctx))) {
                    this.stack.pop()
                    continue
                }
                f.iterLeft--
                this.stack.push(Frame(f.body))
                continue
            }
            if (f.index >= f.steps.size) {
                this.stack.pop()
                continue
            }
            val step = f.steps[f.index++]
            this.ctx.executedSteps++
            if (this.ctx.executedSteps > this.maxSteps) {
                val rec = JsonObject()
                rec.addProperty("cmd", "#max_steps")
                rec.addProperty("ok", false)
                rec.addProperty("error", "超过步数上限 " + this.maxSteps)
                this.failInfo = rec
                this.terminate("超过步数上限 " + this.maxSteps, rec)
                this.done = true
                return
            }
            if (!this.runStep(maid, step)) {
                return // current 已设置，等待子任务
            }
        }
    }

    /** 执行单个 step；返回 true = 立即完成（继续），false = 已挂起子任务 */
    private fun runStep(maid: SmartMaidEntity, step: JsonObject): Boolean {
        // assign：整数算术赋值
        if (step.has("assign")) {
            val variable = step.get("assign").asString
            val expr = if (step.has("expr")) step.get("expr").asString else ""
            val v: Int? = ScriptRef.evalInt(expr, this.ctx)
            if (v != null) {
                this.ctx.vars[variable] = JsonPrimitive(v)
            }
            this.ctx.logStep("assign:$variable", v != null, if (v == null) null else JsonPrimitive(v))
            return true
        }
        // if：条件分支，压入 then/else 帧
        if (step.has("if")) {
            val ok = ScriptRef.evalCond(step.getAsJsonObject("if"), this.ctx)
            val body: JsonArray? = if (ok) {
                if (step.has("then")) step.getAsJsonArray("then") else null
            } else {
                if (step.has("else")) step.getAsJsonArray("else") else null
            }
            if (body != null && body.size() > 0) {
                this.stack.push(Frame(jsonArrayToList(body)))
            }
            this.ctx.logStep("if", ok, null)
            return true
        }
        // loop：压入循环帧
        if (step.has("loop")) {
            val l = step.getAsJsonObject("loop")
            val body: JsonArray = if (l.has("body") && l.get("body").isJsonArray) {
                l.getAsJsonArray("body")
            } else {
                JsonArray()
            }
            val maxIter = boundedInt(l, "max_iter", 20, MAX_ITER_LIMIT)
            val whileCond: JsonObject? = if (l.has("while") && l.get("while").isJsonObject) {
                l.getAsJsonObject("while")
            } else {
                null
            }
            val loopSteps = jsonArrayToList(body)
            if (loopSteps.isNotEmpty()) this.stack.push(Frame(whileCond, loopSteps, maxIter))
            this.ctx.logStep("loop", true, null)
            return true
        }
        // terminate：提前终止 + 回执（result 支持 $var 引用解析）
        if (step.has("terminate")) {
            val t = step.getAsJsonObject("terminate")
            val result: JsonObject? = if (t.has("result") && t.get("result").isJsonObject) {
                ScriptRef.resolve(t.getAsJsonObject("result"), this.ctx)
            } else {
                null
            }
            this.terminate(if (t.has("reason")) t.get("reason").asString else "terminated", result)
            return true
        }
        // 指令 step
        val cmd = if (step.has("cmd")) step.get("cmd").asString else ""
        val params: JsonObject = ScriptRef.resolve(
            if (step.has("params")) step.getAsJsonObject("params") else JsonObject(),
            this.ctx
        )
        this.currentCmd = cmd
        this.currentAs = if (step.has("as")) step.get("as").asString else null
        val task: MaidAITask? = buildSubTask(maid, cmd, params)
        if (task == null) {
            MaidDebug.log(
                "Script 步失败: $cmd（${this.lastSubError}） fail=" +
                    (if (this.failPolicy == FAIL_ABORT) "abort" else "continue")
            )
            val rec = JsonObject()
            rec.addProperty("cmd", cmd)
            rec.addProperty("ok", false)
            if (this.lastSubError != null) {
                rec.addProperty("error", this.lastSubError)
            }
            this.ctx.logStep(cmd, false, rec)
            if (this.failPolicy == FAIL_ABORT) {
                this.failInfo = rec
                this.terminate("步骤失败: $cmd（$lastSubError）", rec)
            }
            return true
        }
        if (!task.canStart(maid)) {
            MaidDebug.log("Script 步前置不满足: $cmd")
            val rec = JsonObject()
            rec.addProperty("cmd", cmd)
            rec.addProperty("ok", false)
            rec.addProperty("error", "前置条件不满足")
            this.ctx.logStep(cmd, false, rec)
            if (this.failPolicy == FAIL_ABORT) {
                this.failInfo = rec
                this.terminate("步骤前置不满足: $cmd", rec)
            }
            return true
        }
        this.current = task
        this.stepStartTick = maid.tickCount
        task.start(maid)
        MaidDebug.log("Script 步开始: $cmd → " + task.taskId())
        return false
    }

    /** 子任务构造：查询原子 / harvest / 禁止嵌套 script / 其余复用 MaidAIBridge */
    private fun buildSubTask(maid: SmartMaidEntity, cmd: String, params: JsonObject): MaidAITask? {
        this.lastSubError = null
        if (ScriptQueries.isQuery(cmd)) {
            val err = StringBuilder()
            val r: JsonObject? = ScriptQueries.query(maid, cmd, params, err)
            if (r == null) {
                this.lastSubError = if (err.isNotEmpty()) err.toString() else "查询失败"
                return null
            }
            return ScriptQueryTask(cmd, r)
        }
        if (cmd == "harvest") {
            val err = StringBuilder()
            val pos: BlockPos? = MaidAIBridge.parsePos(maid, params, "pos", err)
            if (pos == null) {
                this.lastSubError = if (err.isNotEmpty()) err.toString() else "harvest 缺少 pos"
                return null
            }
            val produce = if (params.has("produce")) params.get("produce").asString else null
            val matcher: Predicate<net.minecraft.world.level.block.state.BlockState>? =
                ProduceFilter.compile(produce).matcher
            val count = if (params.has("count")) {
                MaidAIBridge.numericParam(params, "count") ?: run {
                    this.lastSubError = "harvest 的 count 参数不是数字"
                    return null
                }
            } else 0 // 缺省保留 HarvestTask 自身的结构规模上限。
            // 结构类型：显式 structure 参数 > 从 produce 推断（矿石 tag → ore）> 缺省 tree
            val structId = if (params.has("structure")) params.get("structure").asString else null
            val st: StructureType? = if (structId != null) {
                StructureRegistry.get(structId)
            } else if (OreStructure.isOreProduce(produce)) {
                StructureRegistry.get("ore")
            } else {
                StructureRegistry.get("tree")
            }
            if (st == null) {
                this.lastSubError = "未知结构类型: $structId"
                return null
            }
            return HarvestTask(pos, matcher, count, st)
        }
        if (cmd == "script") {
            this.lastSubError = "脚本不允许嵌套 script"
            return null
        }
        val err = StringBuilder()
        val task: MaidAITask? = MaidAIBridge.buildTask(maid, cmd, params, err)
        if (task == null) {
            this.lastSubError = if (err.isNotEmpty()) err.toString() else "未知指令: $cmd"
        }
        return task
    }

    /** 子任务完成：记录步骤（含结构化 result / as 绑定） */
    private fun recordStep() {
        val cur = this.current ?: return
        val r: JsonObject? = cur.resultJson()
        val logResult: JsonElement = r ?: JsonPrimitive(cur.result())
        this.ctx.logStep(this.currentCmd, true, logResult)
        if (this.currentAs != null && r != null) {
            this.ctx.vars[this.currentAs!!] = r
        }
        if (MaidDebug.verbose()) {
            MaidDebug.log("Script 步完成: " + this.currentCmd + " " + logResult)
        }
    }

    private fun terminate(reason: String, result: JsonObject?) {
        this.ctx.terminated = true
        this.ctx.terminateReason = reason
        this.ctx.terminateResult = result
        MaidDebug.log(
            "Script 终止: " + this.ctx.scriptName + " → " + reason +
                "（已执行 " + this.ctx.stepLog.size + " 步）"
        )
    }

    /** 持续型：不因 MaidTaskManager 的 60s 上限被截断，总时长由脚本内部护栏控制。 */
    override fun isContinuous(): Boolean = true

    override fun isDone(): Boolean = this.done || this.cancelled

    override fun result(): String {
        if (this.cancelled) return "脚本已取消"
        if (this.ctx.terminateReason != null) return this.ctx.terminateReason!!
        return if (this.failInfo != null) "脚本执行失败" else "脚本完成"
    }

    override fun forceStop(maid: SmartMaidEntity?) {
        this.current?.forceStop(maid)
        // 正常结束（done 已置位，如 terminate/全部步完成）不算"取消"；
        // 只有外部打断（cancel 指令 / 坐下 / 战斗 / 超时）时才标记 cancelled。
        if (!this.done) {
            this.cancelled = true
        }
        this.done = true
    }

    /** 最终汇总回执（Atomic Command Protocol §七） */
    override fun resultJson(): JsonObject {
        val out = JsonObject()
        out.addProperty("task", "script")
        out.addProperty("name", this.ctx.scriptName)
        out.addProperty(
            "ok",
            !this.cancelled && (this.failInfo == null || this.failPolicy == FAIL_CONTINUE)
        )
        out.addProperty(
            "state",
            if (this.cancelled) "cancelled" else if (this.ctx.terminated) "terminated" else "done"
        )
        out.addProperty(
            "reason",
            if (this.cancelled) "脚本已取消"
            else if (this.ctx.terminateReason != null) this.ctx.terminateReason
            else if (this.failInfo != null) "步骤失败"
            else "完成"
        )
        out.addProperty("completed", this.ctx.stepLog.size)
        val vars = JsonObject()
        for ((key, value) in this.ctx.vars) {
            vars.add(key, value.deepCopy())
        }
        out.add("vars", vars)
        val steps = JsonArray()
        for (s in this.ctx.stepLog) {
            steps.add(s.deepCopy())
        }
        out.add("steps", steps)
        if (this.failInfo != null) {
            out.add("failed", this.failInfo)
        }
        this.ctx.terminateResult?.let {
            out.add("terminated_result", it.deepCopy())
        }
        return out
    }

    // ---------- 内部 ----------

    /** 执行帧：普通 step 列表 或 循环帧 */
    private class Frame {
        val steps: List<JsonObject>
        var index: Int = 0
        val isLoop: Boolean
        val whileCond: JsonObject?
        val body: List<JsonObject>
        var iterLeft: Int = 0

        constructor(steps: List<JsonObject>) {
            this.steps = steps
            this.index = 0
            this.isLoop = false
            this.whileCond = null
            this.body = Collections.emptyList()
            this.iterLeft = 0
        }

        constructor(whileCond: JsonObject?, body: List<JsonObject>, maxIter: Int) {
            this.steps = Collections.emptyList()
            this.index = 0
            this.isLoop = true
            this.whileCond = whileCond
            this.body = body
            this.iterLeft = maxIter
        }
    }

    companion object {
        private const val MAX_SCRIPT_STEPS = 512
        private const val MAX_STEPS_LIMIT = 512
        private const val MAX_ITER_LIMIT = 256
        private const val MAX_DEPTH = 64
        private val CHILD_STEP_KEYS = listOf("steps", "then", "else")

        @JvmStatic
        fun validate(script: JsonObject): String? {
            val steps = script.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return "script 缺少 steps 数组"
            if (steps.size() !in 1..MAX_SCRIPT_STEPS) return "script steps 数量必须在 1..$MAX_SCRIPT_STEPS"
            validateLimit(script, "max_steps", MAX_STEPS_LIMIT)?.let { return it }
            return validateSteps(steps, 0)
        }

        private fun validateSteps(steps: JsonArray, depth: Int): String? {
            if (depth > MAX_DEPTH) return "脚本嵌套过深（上限 $MAX_DEPTH 层）"
            for (element in steps) {
                if (!element.isJsonObject) continue
                val step = element.asJsonObject
                step.get("loop")?.let { raw ->
                    if (!raw.isJsonObject) return "loop 必须是对象"
                    val loop = raw.asJsonObject
                    validateLimit(loop, "max_iter", MAX_ITER_LIMIT, "loop.max_iter")?.let { return it }
                    validateChildSteps(loop.get("body"), "loop.body", depth)?.let { return it }
                }
                for (key in CHILD_STEP_KEYS) {
                    validateChildSteps(step.get(key), key, depth)?.let { return it }
                }
            }
            return null
        }

        private fun validateChildSteps(raw: JsonElement?, key: String, depth: Int): String? {
            if (raw == null) return null
            if (!raw.isJsonArray) return "$key 必须是数组"
            val steps = raw.asJsonArray
            if (steps.size() > MAX_SCRIPT_STEPS) return "$key 数量超过上限 $MAX_SCRIPT_STEPS"
            return validateSteps(steps, depth + 1)
        }

        private fun validateLimit(obj: JsonObject, key: String, max: Int, label: String = key): String? {
            if (!obj.has(key)) return null
            val value = MaidAIBridge.decimalParam(obj.get(key)) ?: return "$label 必须是整数"
            if (value < BigDecimal.ONE || value > BigDecimal.valueOf(max.toLong())) return "$label 必须在 1..$max"
            if (value.stripTrailingZeros().scale() > 0) return "$label 必须是整数"
            return null
        }

        private fun boundedInt(obj: JsonObject, key: String, default: Int, max: Int): Int =
            MaidAIBridge.decimalParam(obj.get(key))?.coerceIn(BigDecimal.ONE, BigDecimal.valueOf(max.toLong()))?.toInt()
                ?: default

        private const val FAIL_ABORT = 0
        private const val FAIL_CONTINUE = 1

        /** 整个脚本最大运行 tick（10 分钟，含移动/收割等耗时段）。 */
        private const val MAX_SCRIPT_TICKS = 20 * 60 * 10
        /** 单个子任务（一步）最大运行 tick（60 秒，防止 move/attack 等卡死）。 */
        private const val MAX_STEP_TICKS = 20 * 60

        private fun jsonArrayToList(arr: JsonArray?): List<JsonObject> {
            val out: MutableList<JsonObject> = ArrayList()
            if (arr == null) return out
            for (index in 0 until minOf(arr.size(), MAX_SCRIPT_STEPS)) {
                val e = arr[index]
                if (e.isJsonObject) {
                    out.add(e.asJsonObject)
                }
            }
            return out
        }
    }
}
