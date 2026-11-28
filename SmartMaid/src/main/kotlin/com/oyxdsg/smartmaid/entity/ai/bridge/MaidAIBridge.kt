package com.oyxdsg.smartmaid.entity.ai.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.craft.CraftExecutor
import com.oyxdsg.smartmaid.entity.ai.maidtask.AttackTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.BuildTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.ChestOpenTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.ChestStoreTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.CollectTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.CraftTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.EatTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.FarmTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.FeedTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.GuardTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidTaskManager
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidTaskNames
import com.oyxdsg.smartmaid.entity.ai.maidtask.MineTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.MoveToTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.OneShotTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.QueueKind
import com.oyxdsg.smartmaid.entity.ai.maidtask.SmeltTask
import com.oyxdsg.smartmaid.entity.ai.maidtask.TransferTask
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionBlockUtil
import com.oyxdsg.smartmaid.entity.ai.script.ScriptTask
import com.oyxdsg.smartmaid.entity.ai.slot.Slots
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.math.BigDecimal

/**
 * AI 指令桥接层（M4）：把外部 AI 的统一 JSON 指令解析成女仆可执行任务，并返回协议回执。
 *
 * 请求格式（对齐 DESIGN_AI_INTERFACE 协议）：
 * ```
 * {"id":"cmd-1","cmd":"mine","params":{"pos":[x,y,z],"range":4,"count":8},
 *  "cancel_previous":true,"persist":false}
 * ```
 *
 * 与 AI 通道解耦：任何通道（桌宠 WebSocket / /maidai 指令 / 未来 LLM）都调用
 * [execute]，本类负责统一解析、参数校验、任务构造、派发到 [MaidTaskManager]
 * 并返回 [MaidCommandResult] 回执。
 *
 * 指令集与 `/maidtasks` 完全一致（attack/guard/feed/mine/farm/build/collect/craft/
 * smelt/transfer/chestopen/chestput/chesttake/move/look/break/place/use/equip/store/drop/
 * pickup/sit/stop/cancel/status）。
 */
object MaidAIBridge {

    /**
     * 执行一条 JSON 指令。
     *
     * @return 回执（从未返回 null；JSON 非法/参数错误/前置不满足均返回失败回执）
     */
    @JvmStatic
    fun execute(maid: SmartMaidEntity, json: String): MaidCommandResult {
        val req: JsonObject = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: JsonSyntaxException) {
            return MaidCommandResult.fail("?", "JSON 解析失败: " + e.message)
        } catch (e: IllegalStateException) {
            return MaidCommandResult.fail("?", "JSON 解析失败: " + e.message)
        }
        val id = if (req.has("id")) req.get("id").asString else "cmd"
        val cmd = if (req.has("cmd")) req.get("cmd").asString else ""
        val params = if (req.has("params") && req.get("params").isJsonObject) req.getAsJsonObject("params") else JsonObject()
        val cancelPrevious = !req.has("cancel_previous") || req.get("cancel_previous").asBoolean
        val persist = req.has("persist") && req.get("persist").asBoolean

        val manager = maid.getMaidTaskManager()

        // 管理指令
        when (cmd) {
            "cancel" -> {
                manager.cancel()
                val result = JsonObject()
                result.addProperty("task", "none")
                return MaidCommandResult.ok(id, "cancelled", "cancelled", result)
            }
            "status" -> {
                val current = manager.currentTask()
                val result = JsonObject()
                result.addProperty("task", current?.taskId())
                result.addProperty("ai_busy", manager.isAiBusy())
                // Q2 向后兼容扩展：追加队列规模（旧调用方忽略新字段即可）
                result.addProperty("short_size", manager.queue().shortSize())
                result.addProperty("long_size", manager.queue().longSize())
                // Q9 向后兼容扩展：追加队列明细（旧调用方忽略新字段即可）
                val snap = manager.snapshot()
                result.add("short", snap.get("short"))
                result.add("long", snap.get("long"))
                return MaidCommandResult.ok(id, "done", "queried", result)
            }
            "queue" -> {
                // 队列操作（Q4；DESIGN_MAID_TASK_QUEUE §十）：promote/stop/move/remove/clear/resume/...
                return handleQueue(manager, id, params)
            }
            "craft_check" -> {
                // 干跑查询（不消耗材料）：供桌宠在自动合成前判断材料是否齐备、
                // 并拿到「缺什么、缺多少」的明细回报给用户/大 AI。
                val checkErr = StringBuilder()
                val item = resolveItem(maid, params, checkErr) ?: return MaidCommandResult.fail(
                    id,
                    if (checkErr.length > 0) checkErr.toString() else "无效物品"
                )
                val report = CraftExecutor.check(maid, ItemStack(item), intParam(params, "count", 1))
                return MaidCommandResult.ok(id, "done", "checked", report)
            }
            "script" -> {
                // 原子层指令协议：AI 一次性规划的多步指令流，模组侧 ScriptTask 顺序执行。
                ScriptTask.validate(params)?.let { return MaidCommandResult.fail(id, it) }
                // script 也可入队（Q9）：queue:true 时与普通任务同样处理（script 不参与 mutex 互斥）
                if (req.has("queue") && req.get("queue").asBoolean) {
                    val kind = if (req.has("long_term") && req.get("long_term").asBoolean) QueueKind.LONG else QueueKind.SHORT
                    val owner = req.has("priority") && "owner" == req.get("priority").asString
                    val priority = if (owner) MaidTaskManager.PRIORITY_OWNER else MaidTaskManager.PRIORITY_DEFAULT
                    val groupId = if (req.has("group") && !req.get("group").isJsonNull) req.get("group").asString else null
                    val parentLabel = if (req.has("group_label") && !req.get("group_label").isJsonNull) req.get("group_label").asString else null
                    val er = manager.enqueueTask(
                        ScriptTask(params), id, "script", params, kind, owner,
                        MaidTaskNames.display("script", params), groupId, parentLabel, priority
                    )
                    return enqueueReceipt(id, kind, er)
                }
                if (!cancelPrevious && manager.isAiBusy()) {
                    return MaidCommandResult.fail(id, "女仆已有任务运行中（cancel_previous=false）")
                }
                val started = manager.setTask(ScriptTask(params))
                if (!started) {
                    return MaidCommandResult.fail(id, "脚本前置条件不满足")
                }
                val scriptOk = JsonObject()
                scriptOk.addProperty("task", "script")
                scriptOk.addProperty("note", "脚本已下发")
                return MaidCommandResult.ok(id, "running", "accepted", scriptOk)
            }
            else -> {
            }
        }

        // 任务指令
        val err = StringBuilder()
        val task = buildTask(maid, cmd, params, err) ?: return MaidCommandResult.fail(
            id,
            if (err.length > 0) err.toString() else "未知指令: $cmd"
        )
        // 队列扩展（Q2）：仅显式 queue:true 才入队；缺省仍是即时抢占，语义不变（§三 兼容铁律）。
        if (req.has("queue") && req.get("queue").asBoolean) {
            val kind = if (req.has("long_term") && req.get("long_term").asBoolean) QueueKind.LONG else QueueKind.SHORT
            val owner = req.has("priority") && "owner" == req.get("priority").asString
            val priority = if (owner) MaidTaskManager.PRIORITY_OWNER else MaidTaskManager.PRIORITY_DEFAULT
            val groupId = if (req.has("group") && !req.get("group").isJsonNull) req.get("group").asString else null
            val parentLabel = if (req.has("group_label") && !req.get("group_label").isJsonNull) req.get("group_label").asString else null
            val er = manager.enqueueTask(
                task, id, cmd, params, kind, owner, MaidTaskNames.display(cmd, params),
                groupId, parentLabel, priority
            )
            return enqueueReceipt(id, kind, er)
        }
        if (!cancelPrevious && manager.isAiBusy()) {
            return MaidCommandResult.fail(id, "女仆已有任务运行中（cancel_previous=false）")
        }
        val started = manager.setTask(task)
        if (persist) {
            MaidTaskConfig.save(maid, cmd, params)
        }
        if (!started) {
            return MaidCommandResult.fail(id, "任务前置条件不满足: " + task.taskId())
        }
        val result = JsonObject()
        result.addProperty("task", task.taskId())
        result.addProperty("note", "任务已下发")
        return MaidCommandResult.ok(id, "running", "accepted", result)
    }

    /** 入队判定结果 → 协议回执（§8.7：拒绝/重复/满/合并/软提示都要可观测）。 */
    private fun enqueueReceipt(id: String, kind: QueueKind, er: MaidTaskManager.EnqueueResult): MaidCommandResult {
        if (!er.ok) {
            val fail = JsonObject()
            fail.addProperty("reason", er.reason)
            if (er.existingId != null) {
                fail.addProperty("existing_id", er.existingId)
            }
            if (er.note != null) {
                fail.addProperty("note", er.note)
            }
            return MaidCommandResult(id, false, "failed", "rejected", fail)
        }
        val ok = JsonObject()
        ok.addProperty("id", er.existingId)
        ok.addProperty("queue", kind.name)
        if (er.merged) {
            ok.addProperty("merged", true)
        } else {
            ok.addProperty("queued", true)
            if (er.overlap.isNotEmpty()) {
                val arr = JsonArray()
                er.overlap.forEach { arr.add(it) }
                ok.add("possible_overlap", arr)
            }
        }
        if (er.note != null) {
            ok.addProperty("note", er.note)
        }
        return MaidCommandResult.ok(id, "queued", if (er.merged) "merged" else "enqueued", ok)
    }

    // ---------- 队列操作（Q4） ----------

    /**
     * 队列操作命令：`{"cmd":"queue","params":{"op":"promote","id":"L1"}}`。
     * op = query / cancelCurrent / promote / stop / remove / moveUp / move / clear / resume。
     */
    private fun handleQueue(manager: MaidTaskManager, id: String, params: JsonObject): MaidCommandResult {
        val op = if (params.has("op")) params.get("op").asString else "query"
        val result = JsonObject()
        return when (op) {
            "query" -> MaidCommandResult.ok(id, "done", "queried", manager.snapshot())
            "cancelCurrent" -> {
                val had = manager.queue().hasCurrent()
                manager.cancel()
                result.addProperty("cancelled", had)
                MaidCommandResult.ok(id, "done", "cancelCurrent", result)
            }
            "pauseCurrent" -> if (manager.pauseCurrent()) {
                MaidCommandResult.ok(id, "done", "pauseCurrent", result)
            } else {
                MaidCommandResult.fail(id, "没有正在执行的任务")
            }
            "setLongTerm" -> {
                val lt = params.has("long_term") && params.get("long_term").asBoolean
                val taskId = strParam(params, "id")
                if (manager.setLongTerm(taskId, lt)) {
                    MaidCommandResult.ok(id, "done", "setLongTerm", result)
                } else {
                    MaidCommandResult.fail(id, "无法调整（未找到或目标队列满）: $taskId")
                }
            }
            "stopGroup" -> {
                val removed = manager.stopGroup(strParam(params, "group"))
                result.addProperty("removed", removed)
                MaidCommandResult.ok(id, "done", "stopGroup", result)
            }
            "promote" -> {
                // 长期项「设为当前」：可打断短期（当前项 PAUSED 降位保留）
                val t = manager.queue().promote(strParam(params, "id"))
                if (t == null) {
                    MaidCommandResult.fail(id, "未找到长期任务: " + strParam(params, "id"))
                } else {
                    result.addProperty("promoted", t.id())
                    MaidCommandResult.ok(id, "done", "promote", result)
                }
            }
            "stop" -> {
                // 长期「停止」= 永久移除（含正在执行的那一项）
                if (manager.stopTask(strParam(params, "id"))) {
                    MaidCommandResult.ok(id, "done", "stop", result)
                } else {
                    MaidCommandResult.fail(id, "未找到任务: " + strParam(params, "id"))
                }
            }
            "remove" -> {
                if (manager.queue().remove(queueKind(params), strParam(params, "id"))) {
                    MaidCommandResult.ok(id, "done", "remove", result)
                } else {
                    MaidCommandResult.fail(id, "未找到任务: " + strParam(params, "id"))
                }
            }
            "moveUp" -> {
                if (manager.queue().moveUp(queueKind(params), strParam(params, "id"))) {
                    MaidCommandResult.ok(id, "done", "moveUp", result)
                } else {
                    MaidCommandResult.fail(id, "无法上移: " + strParam(params, "id"))
                }
            }
            "move" -> {
                val to = intParam(params, "to", 0)
                if (manager.queue().move(queueKind(params), strParam(params, "id"), to)) {
                    MaidCommandResult.ok(id, "done", "move", result)
                } else {
                    MaidCommandResult.fail(id, "无法移动: " + strParam(params, "id"))
                }
            }
            "clear" -> {
                manager.queue().clear(queueKind(params))
                MaidCommandResult.ok(id, "done", "clear", result)
            }
            "resume" -> {
                if (manager.queue().resume(strParam(params, "id"))) {
                    MaidCommandResult.ok(id, "done", "resume", result)
                } else {
                    MaidCommandResult.fail(id, "未找到暂停项: " + strParam(params, "id"))
                }
            }
            else -> MaidCommandResult.fail(id, "未知队列操作: $op")
        }
    }

    private fun strParam(params: JsonObject, key: String): String =
        if (params.has(key) && !params.get(key).isJsonNull) params.get(key).asString else ""

    /** 队列类型参数 kind（默认 SHORT）。 */
    private fun queueKind(params: JsonObject): QueueKind =
        if ("LONG".equals(strParam(params, "kind"), ignoreCase = true)) QueueKind.LONG else QueueKind.SHORT

    // ---------- 指令 → 任务构造 ----------

    /** 供 [ScriptTask] 复用：把脚本 step 的指令参数构造成子任务（含集成任务/基础任务）。 */
    @JvmStatic
    fun buildTask(maid: SmartMaidEntity, cmd: String, params: JsonObject, err: StringBuilder): MaidAITask? {
        return when (cmd) {
            "attack" -> {
                val targetType = resolveEntityType(params, err)
                if (err.length > 0) {
                    null
                } else {
                    AttackTask(intParam(params, "range", 12), targetType)
                }
            }
            "guard" -> GuardTask(intParam(params, "range", 16), boolParam(params, "defensive", false))
            "feed" -> FeedTask()
            "eat" -> eatTask(maid, params, err)
            "mine" -> {
                // 坐标可省：不给 pos 时以女仆当前脚下为中心自动探测周围矿物
                // （range 默认 12 覆盖水平整片矿脉，垂直由 MineTask 内部 8 格；
                // 给了 pos 则按指定范围）
                var pos = parsePos(maid, params, "pos", err)
                if (pos == null) {
                    err.setLength(0)   // pos 可选，缺省不算错误
                    pos = maid.blockPosition()
                }
                MineTask(pos, intParam(params, "range", 12), intParam(params, "count", 8))
            }
            "farm" -> {
                val pos = parsePos(maid, params, "pos", err)
                if (pos == null) {
                    null
                } else {
                    FarmTask(pos, intParam(params, "range", 4))
                }
            }
            "build" -> {
                val pos = parsePos(maid, params, "pos", err)
                if (pos == null) {
                    null
                } else if (params.has("blocks")) {
                    BuildTask(parseBlocks(maid, params.get("blocks"), err))
                } else {
                    BuildTask(stackUp(pos, intParam(params, "height", 1)))
                }
            }
            "collect" -> CollectTask(intParam(params, "range", 8).toDouble())
            "craft" -> craftTask(maid, params, err)
            "smelt" -> smeltTask(maid, params, err)
            "transfer" -> transferTask(maid, params, err)
            "chestopen" -> {
                val pos = parsePos(maid, params, "pos", err)
                if (pos == null) {
                    null
                } else {
                    ChestOpenTask(pos)
                }
            }
            "chestput" -> chestPutTask(maid, params, err)
            "chesttake" -> chestTakeTask(maid, params, err)
            "cheststore" -> {
                // 存箱打包成一个原子队列项（开箱 → 放入）
                val pos = parsePos(maid, params, "pos", err)
                if (pos == null) {
                    null
                } else {
                    ChestStoreTask(pos, intParam(params, "count", 64))
                }
            }
            "move" -> {
                val pos = parsePos(maid, params, "pos", err)
                if (pos == null) {
                    null
                } else {
                    MoveToTask(pos, 1.0, 1.5)
                }
            }
            "look" -> {
                val pos = parsePos(maid, params, "pos", err)
                if (pos == null) {
                    null
                } else {
                    OneShotTask("look", "已面朝目标") {
                        maid.getLookControl().setLookAt(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                    }
                }
            }
            "break" -> {
                val pos = parsePos(maid, params, "pos", err)
                if (pos == null) {
                    null
                } else {
                    OneShotTask("break", "已挖方块") { MaidActions.breakBlock(maid, pos) }
                }
            }
            "place" -> {
                val pos = parsePos(maid, params, "pos", err)
                if (pos == null) {
                    null
                } else {
                    OneShotTask("place", "已放置方块") { MaidActions.placeBlock(maid, pos.below(), Direction.UP) }
                }
            }
            "use" -> {
                val pos = parsePos(maid, params, "pos", err)
                if (pos == null) {
                    null
                } else {
                    OneShotTask("use", "已使用物品") { MaidActions.useItemOn(maid, pos.below(), Direction.UP) }
                }
            }
            "equip" -> {
                val item = resolveItem(maid, params, err)
                if (item == null) {
                    null
                } else {
                    OneShotTask("equip", "已装备到主手") {
                        MaidActions.equipFromBackpack(maid) { stack -> stack.`is`(item) }
                    }
                }
            }
            "store" -> OneShotTask("store", "已收纳主手物品") {
                val hand = maid.mainHandItem
                if (!hand.isEmpty) {
                    val left = MaidActions.storeToBackpack(maid, hand, 1)
                    // 主手槽 0 属于女仆背包；setItemInHand 只改装备栏，源槽不清会造成复制。
                    maid.getMaidInventory().setItem(0, left)
                    maid.getMaidInventory().setChanged()
                    maid.syncInventoryArmor()
                }
            }
            "drop" -> OneShotTask("drop", "已丢出") {
                val hand = maid.mainHandItem
                if (!hand.isEmpty) {
                    val drop = hand.split(minOf(intParam(params, "count", 1), hand.count))
                    // 丢到主人身边（丢在自己脚下会被立刻捡回）
                    MaidActions.dropToOwner(maid, drop)
                    maid.syncInventoryArmor()
                }
            }
            "pickup" -> CollectTask(intParam(params, "range", 4).toDouble())
            "sit" -> OneShotTask("sit", "已切换坐姿") {
                // 坐/站统一切换：落地吸附 + 移动锁定都在 setOrderedToSit 内处理
                maid.setOrderedToSit(!maid.isOrderedToSit)
            }
            "stop" -> OneShotTask("stop", "已停止") {
                maid.getNavigation().stop()
                maid.getMaidTaskManager().cancel()
            }
            else -> null
        }
    }

    /** eat 指令：无 item 参数则吃背包里营养最高的食物。 */
    private fun eatTask(maid: SmartMaidEntity, params: JsonObject, err: StringBuilder): MaidAITask? {
        if (!params.has("item")) {
            return EatTask(null)
        }
        val item = resolveItem(maid, params, err)
        return if (err.length > 0) null else EatTask(item)
    }

    private fun craftTask(maid: SmartMaidEntity, params: JsonObject, err: StringBuilder): MaidAITask? {
        val item = resolveItem(maid, params, err) ?: return null
        return CraftTask(ItemStack(item), intParam(params, "count", 1))
    }

    private fun smeltTask(maid: SmartMaidEntity, params: JsonObject, err: StringBuilder): MaidAITask? {
        val item = resolveItem(maid, params, err) ?: return null
        return SmeltTask(ItemStack(item), intParam(params, "count", 1))
    }

    private fun transferTask(maid: SmartMaidEntity, params: JsonObject, err: StringBuilder): MaidAITask? {
        val fromSpec = if (params.has("from")) params.get("from").asString else ""
        val toSpec = if (params.has("to")) params.get("to").asString else ""
        val from = Slots.parse(fromSpec)
        val to = Slots.parse(toSpec)
        if (from == null) {
            err.append("无效源槽位: ").append(fromSpec)
            return null
        }
        if (to == null) {
            err.append("无效目标槽位: ").append(toSpec)
            return null
        }
        return TransferTask(from, to, intParam(params, "count", 64))
    }

    private fun chestPutTask(maid: SmartMaidEntity, params: JsonObject, err: StringBuilder): MaidAITask? {
        val pos = maid.getOpenedContainer()
        if (pos == null || !PerceptionBlockUtil.isContainerAt(maid.level(), pos)) {
            err.append("女仆还没打开箱子（或箱子不在了），先 chestopen")
            return null
        }
        return TransferTask(Slots.mainhand(), Slots.container(pos, -1), intParam(params, "count", 64))
    }

    private fun chestTakeTask(maid: SmartMaidEntity, params: JsonObject, err: StringBuilder): MaidAITask? {
        val pos = maid.getOpenedContainer()
        if (pos == null || !PerceptionBlockUtil.isContainerAt(maid.level(), pos)) {
            err.append("女仆还没打开箱子（或箱子不在了），先 chestopen")
            return null
        }
        return TransferTask(
            Slots.container(pos, intParam(params, "slot", -1)),
            Slots.mainhand(),
            intParam(params, "count", 1)
        )
    }

    // ---------- 参数解析 ----------

    private val INT_BOUNDS = mapOf(
        "range" to 1..32, "max_range" to 1..32, "expand" to 1..32,
        "count" to 1..64, "height" to 1..32, "to" to 0..64, "slot" to -1..53,
    )

    @JvmStatic
    fun clampParam(key: String, value: Int): Int = INT_BOUNDS[key]?.let(value::coerceIn) ?: value

    @JvmStatic
    fun intParam(params: JsonObject, key: String, def: Int): Int = numericParam(params, key) ?: def

    internal fun numericParam(params: JsonObject, key: String): Int? {
        val value = decimalParam(params.get(key)) ?: return null
        val bounds = INT_BOUNDS[key] ?: (Int.MIN_VALUE..Int.MAX_VALUE)
        return value.coerceIn(BigDecimal.valueOf(bounds.first.toLong()), BigDecimal.valueOf(bounds.last.toLong())).toInt()
    }

    internal fun decimalParam(element: com.google.gson.JsonElement?): BigDecimal? {
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        return try {
            element.asBigDecimal
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun boolParam(params: JsonObject, key: String, def: Boolean): Boolean =
        if (params.has(key) && params.get(key).isJsonPrimitive && params.get(key).asJsonPrimitive.isBoolean) {
            params.get(key).asBoolean
        } else {
            def
        }

    /** 解析坐标参数：[x,y,z]，元素可为数字或 ~ / ~5（以女仆位置为基准）。供查询原子/脚本复用。 */
    @JvmStatic
    fun parsePos(maid: SmartMaidEntity, params: JsonObject, key: String, err: StringBuilder): BlockPos? {
        if (!params.has(key) || !params.get(key).isJsonArray) {
            err.append("缺少坐标参数: ").append(key).append("（格式 [x,y,z]）")
            return null
        }
        val arr = params.getAsJsonArray(key)
        if (arr.size() < 3) {
            err.append("坐标参数需 3 个分量: ").append(key)
            return null
        }
        val base = maid.blockPosition()
        val x = parseCoord(base.x, arr.get(0))
        val y = parseCoord(base.y, arr.get(1))
        val z = parseCoord(base.z, arr.get(2))
        if (x == null || y == null || z == null) {
            err.append("坐标分量无效: ").append(key)
            return null
        }
        return BlockPos(x, y, z)
    }

    private fun parseCoord(base: Int, e: JsonElement): Int? {
        if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) {
            return e.asInt
        }
        val s: String = try {
            e.asString
        } catch (_: Exception) {
            return null
        }
        if (s.startsWith("~")) {
            val rest = s.substring(1).trim()
            if (rest.isEmpty()) {
                return base
            }
            return try {
                base + rest.toInt()
            } catch (_: NumberFormatException) {
                null
            }
        }
        return try {
            s.toInt()
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** 解析可选的实体类型参数 target（如 pig / minecraft:pig）；未给返回 null，非法则写 err。 */
    private fun resolveEntityType(params: JsonObject, err: StringBuilder): EntityType<*>? {
        if (!params.has("target") || params.get("target").isJsonNull) {
            return null
        }
        val id = params.get("target").asString
        val identifier = Identifier.tryParse(id)
        val type = if (identifier == null) null
        else BuiltInRegistries.ENTITY_TYPE.get(identifier).map { it.value() }.orElse(null)
        if (type == null) {
            err.append("未找到实体类型: ").append(id)
        }
        return type
    }

    private fun resolveItem(maid: SmartMaidEntity, params: JsonObject, err: StringBuilder): Item? {
        if (!params.has("item")) {
            err.append("缺少物品参数: item")
            return null
        }
        val id = params.get("item").asString
        val identifier = Identifier.tryParse(id)
        if (identifier == null) {
            err.append("无效物品 id: ").append(id)
            return null
        }
        val item = BuiltInRegistries.ITEM.get(identifier).map { it.value() }.orElse(null)
        if (item == null) {
            err.append("未找到物品: ").append(id)
        }
        return item
    }

    /** 解析 blocks 列表（build 用）：[[x,y,z],...] */
    private fun parseBlocks(maid: SmartMaidEntity, blocks: JsonElement, err: StringBuilder): List<BlockPos> {
        val list = ArrayList<BlockPos>()
        if (blocks.isJsonArray) {
            for (e in blocks.asJsonArray) {
                if (e.isJsonArray && e.asJsonArray.size() >= 3) {
                    val a = e.asJsonArray
                    val base = maid.blockPosition()
                    val x = parseCoord(base.x, a.get(0))
                    val y = parseCoord(base.y, a.get(1))
                    val z = parseCoord(base.z, a.get(2))
                    if (x != null && y != null && z != null) {
                        list.add(BlockPos(x, y, z))
                    }
                }
            }
        }
        if (list.isEmpty()) {
            err.append("blocks 列表为空或格式无效")
        }
        return list
    }

    /** 从 pos 向上堆叠 height 个方块（同 /maidtasks build） */
    private fun stackUp(pos: BlockPos, height: Int): List<BlockPos> {
        val positions = ArrayList<BlockPos>()
        for (i in 0 until maxOf(1, height)) {
            positions.add(pos.offset(0, i, 0))
        }
        return positions
    }
}
