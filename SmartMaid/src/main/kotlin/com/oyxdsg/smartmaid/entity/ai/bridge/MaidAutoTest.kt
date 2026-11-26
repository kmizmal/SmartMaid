package com.oyxdsg.smartmaid.entity.ai.bridge

import com.google.gson.*
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidInventoryTidy
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.Container
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.state.BlockState
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * 女仆自动测试钩子（开发用）：服务端 tick 驱动，读配置文件自动执行一条条 JSON 指令，
 * 结果写日志。用于"代码层自动化测试"，无需模拟鼠标/键盘操作游戏界面。
 *
 * <p>配置文件：{@code config/smartmaid/autotest.json}，格式为 JSON 数组：</p>
 * <pre>{@code
 * [
 *   {"id":"t01","cmd":"status"},
 *   {"id":"g1","give":{"minecraft:oak_log":8,"minecraft:iron_ingot":5}},  // 预置背包
 *   {"id":"t02","cmd":"craft_check","params":{"item":"minecraft:iron_pickaxe","count":1},
 *    "expect":{"ok":true,"result.craftable":true}}                          // 回执断言
 * ]
 * }</pre>
 *
 * <p>支持五种条目（2026-09-29 扩展 check / run）：</p>
 * <ul>
 *   <li>{@code cmd}：普通 JSON 指令（走 {@link MaidAIBridge}）</li>
 *   <li>{@code give}：给女仆背包预置物品（0-35 背包区，同类堆叠/空槽）——测试前准备材料用</li>
 *   <li>{@code expect}：可挂在 cmd 条目上，对回执做扁平字段断言（键为点分路径，如
 *       {@code ok} / {@code result.craftable}），全部匹配记 PASS，否则记 FAIL</li>
 *   <li>{@code run}：执行一条命令（测试准备用，如 setblock 造环境）；用服务器控制台源执行
 *       （不需 OP），{@code {x}}/{@code {y}}/{@code {z}} 会替换为女仆方块坐标</li>
 *   <li>{@code check}：对世界/背包/规则做断言（见 {@link #runCheck}），用于验证"指令执行后
 *       世界真的变了"—— 例如方块是否被破坏、掉落是否进了背包、搭路白名单判定、数据包 tag 是否生效</li>
 * </ul>
 *
 * <pre>{@code
 * [
 *   {"id":"p1","run":"setblock {x} {y} {z} minecraft:stone"},              // 在女仆脚下造方块
 *   {"id":"b1","cmd":"break","params":{"pos":[x,y,z]}},                    // 让女仆挖掉它
 *   {"id":"c1","check":{"block":{"offset":[0,0,0],"is":"minecraft:air"}}}, // 断言已挖空
 *   {"id":"c2","check":{"inv":{"item":"minecraft:cobblestone","min":1}}},  // 断言掉落进了背包
 *   {"id":"c3","check":{"bridge":{"item":"minecraft:diamond_block","allowed":false}}}
 * ]
 * }</pre>
 *
 * <p>流程：有玩家在线但无女仆 → 自动执行 {@code summonmaid} 召唤；有女仆 →
 * 逐条执行并记录回执；任务型指令（回执 {@code running}）执行后自动等待约 2 秒
 * 再取下一条，保证任务真正跑完（否则连续 craft 会被 {@code aiBusy} 拒绝）。
 * 全部执行完把配置文件改名为 {@code autotest.done.json} 防重复。
 * 无配置文件时完全静默（生产无影响）。</p>
 */
object MaidAutoTest {

    private val CONFIG: Path = FabricLoader.getInstance().configDir
        .resolve("smartmaid").resolve("autotest.json")
    private val EXECUTED = AtomicBoolean(false)

    /** 测试用：下一次破坏是否被 BEFORE 事件否决（验证"破坏走原版流程、保护可拦截"） */
    @Volatile
    private var vetoNextBreak = false

    /** 断言计数（结束时输出汇总，省得逐行数） */
    private var PASS_COUNT = 0
    private var FAIL_COUNT = 0

    /**
     * 最近一次 {@code run} 里 setblock 的坐标。
     *
     * <p>存在的理由：女仆会跟随玩家移动，所以"相对女仆当前位置"的断言（offset）在等待若干秒后会指向
     * 别处 —— 第一次真机测试就栽在这里（v04 FAIL，而事件其实已被正确否决）。
     * 用绝对坐标做断言/清理才稳定，故提供 {@code check:{"at":"lastRun"}} 与 {@code {lx}/{ly}/{lz}} 占位符。</p>
     */
    private var lastRunBlock: BlockPos? = null

    /** 匹配 run 命令里的 setblock 坐标 */
    private val SETBLOCK_POS =
        Pattern.compile("setblock\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)")

    /** 匹配 run 命令里的 maidtasks move 目标坐标（用于 record 轨迹判定） */
    private val MOVE_POS =
        Pattern.compile("maidtasks\\s+move\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)")

    private var LAST_RECORD: RecordSummary? = null

    init {
        // 注册"测试否决器"：只有配置显式打开 vetoNextBreak 时才拦一次，
        // 用来证明 PlayerBlockBreakEvents 链路真的接通（生产环境永远不影响。
        // 领地保护等 mod 正是通过同一个事件拦截的）。
        PlayerBlockBreakEvents.BEFORE.register { _, _, pos, _, _ ->
            if (vetoNextBreak) {
                vetoNextBreak = false
                SmartMaid.LOGGER.info("AutoTest: 破坏被事件否决 @ $pos")
                false
            } else {
                true
            }
        }
    }

    private var LIST: JsonArray? = null        // 已加载的测试序列
    private var IDX = 0                        // 当前执行到第几条
    private var WAIT_TICKS = 0                 // 任务型指令后的等待 tick
    private var RECORD: RecordState? = null    // P3 标定：逐 tick 采样女仆位置
    private var ANCHOR: BlockPos? = null       // P3 标定：重复场景的固定起点

    private const val STEP_TICKS = 10          // 状态机推进间隔
    private const val TASK_WAIT_TICKS = 40     // 任务型指令后等待（≈2 秒）

    private data class RecordState(
        val id: String,
        var remaining: Int,
        val target: BlockPos?,
        val samples: MutableList<String> = ArrayList()
    )

    /** 一次 record 的几何摘要，供 check:{"record":...} 做正式断言。 */
    private data class RecordSummary(
        val id: String,
        val samples: Int,
        val minY: Double,
        val maxY: Double,
        val minTargetDist: Double?,
        val target: BlockPos?
    )

    /** 由 ServerTickEvents.END_SERVER_TICK 注册调用（仅服务端触发） */
    @JvmStatic
    fun onServerTick(server: MinecraftServer) {
        RECORD?.let { rec ->
            val player = server.playerList.players.firstOrNull() ?: return
            val maid = findMaid(player) ?: return
            rec.samples.add(
                String.format(
                    java.util.Locale.ROOT,
                    "%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%b",
                    server.tickCount, maid.x, maid.y, maid.z,
                    maid.deltaMovement.x, maid.deltaMovement.y, maid.deltaMovement.z,
                    maid.onGround()
                )
            )
            rec.remaining--
            if (rec.remaining <= 0) {
                SmartMaid.LOGGER.info("AutoTest [${rec.id}] record: ${rec.samples.joinToString(";")}")
                finishRecord(rec)
                RECORD = null
            }
            return
        }
        if (EXECUTED.get() || server.tickCount % STEP_TICKS != 0) {
            return
        }
        if (!Files.exists(CONFIG)) {
            return
        }
        val player = server.playerList.players.firstOrNull() ?: return // 无玩家在线，等待
        val maid = findMaid(player)
        if (maid == null) {
            // 无女仆：尝试召唤，下次 tick 再检测
            try {
                server.commands.dispatcher.execute(
                    "summonmaid", player.createCommandSourceStack()
                )
                SmartMaid.LOGGER.info("AutoTest: 未找到女仆，已执行 summonmaid")
            } catch (e: Exception) {
                SmartMaid.LOGGER.info("AutoTest: summonmaid 失败: " + e.message)
            }
            return
        }
        run(player, maid)
    }

    /** 状态机：每 STEP_TICKS tick 推进一条；任务型回执后等 TASK_WAIT_TICKS。 */
    private fun run(player: ServerPlayer, maid: SmartMaidEntity) {
        if (LIST == null && !load()) {
            return // 配置不存在 / 加载失败（load 内已处理）
        }
        if (WAIT_TICKS > 0) {
            WAIT_TICKS--
            return // 等待前一条任务完成
        }
        val list = LIST!!
        if (IDX >= list.size()) {
            finish()
            return
        }
        val element = list.get(IDX)
        IDX++
        if (!element.isJsonObject) {
            return
        }
        val req = element.asJsonObject
        val id = if (req.has("id")) req.get("id").asString else "?"

        // 纯注释条目（如 {"__comment": "..."}）跳过，不当作指令执行
        if (!req.has("cmd") && !req.has("give") && !req.has("run") && !req.has("check")
            && !req.has("tidy") && !req.has("vetoNextBreak") && !req.has("expect") && !req.has("wait")
            && !req.has("anchor") && !req.has("tp")
        ) {
            return
        }

        // P3 标定：记录/回到固定起点，便于同一世界重复测量。
        if (req.has("anchor")) {
            ANCHOR = maid.blockPosition()
            SmartMaid.LOGGER.info("AutoTest [$id] anchor=$ANCHOR")
            return
        }
        if (req.has("tp")) {
            val target = ANCHOR
            if (target == null) {
                SmartMaid.LOGGER.info("AutoTest [$id] tp 失败：尚未设置 anchor")
                return
            }
            maid.maidTaskManager.cancel()
            maid.actionExecutor.reset()
            maid.teleportTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5)
            maid.setDeltaMovement(0.0, 0.0, 0.0)
            player.teleportTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5)
            maid.getNavigation().stop()
            maid.getMoveControl().setWait()
            SmartMaid.LOGGER.info("AutoTest [$id] tp=$target")
            return
        }

        // vetoNextBreak：武装"测试否决器"，用于验证破坏事件链（见静态块）
        if (req.has("vetoNextBreak")) {
            vetoNextBreak = req.get("vetoNextBreak").asBoolean
            SmartMaid.LOGGER.info("AutoTest [$id] vetoNextBreak=$vetoNextBreak")
            return
        }

        // wait：显式等待若干个状态机步（每步 STEP_TICKS tick），用于等任务/同步落定。
        if (req.has("wait")) {
            WAIT_TICKS = req.get("wait").asInt.coerceAtLeast(0)
            SmartMaid.LOGGER.info("AutoTest [$id] wait=" + WAIT_TICKS + " 步")
            return
        }

        // give：预置女仆背包（AutoTest 专用，非指令）
        if (req.has("give")) {
            giveItems(maid, req.getAsJsonObject("give"))
            SmartMaid.LOGGER.info("AutoTest [$id] give: " + req.get("give"))
            return
        }

        // tidy：立即执行一次背包整理（合并同类 + 丢弃 #smartmaid:junk）
        if (req.has("tidy")) {
            val r = MaidInventoryTidy.tidy(maid)
            SmartMaid.LOGGER.info(
                "AutoTest [$id] tidy: 合并=" + r.merged
                    + " 丢弃=" + r.dropped + " 空槽=" + freeSlots(maid)
            )
            return
        }

        // run：执行一条命令（测试准备或命令执行回归用）。
        // source 默认 server（控制台源，权限最高）；source=player 时使用真实玩家命令源，
        // 走服务器 CommandDispatcher 与玩家权限上下文。{x}/{y}/{z} 会替换为女仆方块坐标。
        if (req.has("run")) {
            val cmd = req.get("run").asString
            val source = if (req.has("source")) req.get("source").asString.lowercase() else "server"
            val expectResult = if (req.has("expectResult")) req.get("expectResult").asInt else null
            val expectError = if (req.has("expectError")) req.get("expectError").asBoolean else null
            val expectTask = if (req.has("expectTask")) req.get("expectTask").asString else null
            val expectYaw = if (req.has("expectYaw")) req.get("expectYaw").asDouble else null
            val yawTolerance = if (req.has("yawTolerance")) req.get("yawTolerance").asDouble else 2.0
            runCommand(player, maid, id, cmd, source, expectResult, expectError, expectTask, expectYaw, yawTolerance)
            if (req.has("recordTicks")) {
                val ticks = req.get("recordTicks").asInt.coerceAtLeast(1)
                RECORD = RecordState(id, ticks, parseMoveTarget(maid, cmd))
            }
            return
        }

        // check：对世界/背包/规则做断言（AutoTest 专用，非指令）
        if (req.has("check")) {
            val check = req.getAsJsonObject("check")
            val ok = runCheck(maid, check)
            count(ok)
            SmartMaid.LOGGER.info("AutoTest [$id] 检查=" + (if (ok) "PASS" else "FAIL") + " " + check)
            return
        }

        // 指令 JSON 同样支持位置占位符（{lx}/{ly}/{lz}、{x}/{x+1}…），
        // 这样 break 能用绝对坐标瞄准"刚 setblock 的那个方块"，不受女仆移动影响
        val json = expandPos(maid, req.toString())
        val result = MaidAIBridge.execute(maid, json)
        if (req.has("expect")) {
            val ok = checkExpect(req.getAsJsonObject("expect"), result)
            count(ok)
            SmartMaid.LOGGER.info(
                "AutoTest [$id] 断言=" + (if (ok) "PASS" else "FAIL")
                    + " 期望=" + req.get("expect") + " 实际=" + result.toJson()
            )
        } else {
            SmartMaid.LOGGER.info("AutoTest [$id] 回执=" + result.toJson())
        }
        if (result.state == "running") {
            WAIT_TICKS = TASK_WAIT_TICKS // 任务型：等它跑完再取下一条
        }
    }

    private fun load(): Boolean {
        if (!Files.exists(CONFIG)) {
            return false
        }
        return try {
            val text = Files.readString(CONFIG, StandardCharsets.UTF_8)
            LIST = JsonParser.parseString(text).asJsonArray
            IDX = 0
            WAIT_TICKS = 0
            PASS_COUNT = 0
            FAIL_COUNT = 0
            SmartMaid.LOGGER.info(
                "AutoTest: 开始执行测试序列，共 " + LIST!!.size()
                    + " 条，配置文件 " + CONFIG
            )
            true
        } catch (e: Exception) {
            SmartMaid.LOGGER.error("AutoTest 配置加载失败", e)
            SmartMaid.LOGGER.info("AutoTest: 配置加载失败: " + e.message)
            EXECUTED.set(true)
            false
        }
    }

    private fun finish() {
        EXECUTED.set(true)
        LIST = null
        IDX = 0
        WAIT_TICKS = 0
        // 汇总行：测试结果走 LOGGER（不能用 MaidDebug —— 它默认关闭，会把结果全静音）
        SmartMaid.LOGGER.info(
            "AutoTest 汇总: PASS=" + PASS_COUNT + " FAIL=" + FAIL_COUNT
                + (if (FAIL_COUNT == 0) " （全部通过）" else " （有失败项，见上面 FAIL 行）")
        )
        try {
            val done = CONFIG.resolveSibling("autotest.done.json")
            Files.move(CONFIG, done, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            SmartMaid.LOGGER.info("AutoTest: 执行完成，配置已标记为 " + done.fileName)
        } catch (e: Exception) {
            SmartMaid.LOGGER.info("AutoTest: 标记完成失败: " + e.message)
        }
    }

    /** 给女仆背包预置物品（0-35 背包区，同类堆叠/空槽）。 */
    private fun giveItems(maid: SmartMaidEntity, give: JsonObject) {
        for ((itemId, value) in give.entrySet()) {
            val count = try {
                value.asInt
            } catch (ex: NumberFormatException) {
                SmartMaid.LOGGER.info("AutoTest give: 数量非法 $itemId")
                continue
            }
            if (count <= 0) {
                continue
            }
            val item = resolveItem(itemId)
            if (item == null) {
                SmartMaid.LOGGER.info("AutoTest give: 未找到物品 $itemId")
                continue
            }
            val left = MaidActions.storeToBackpack(maid, ItemStack(item, count))
            if (!left.isEmpty) {
                SmartMaid.LOGGER.info(
                    "AutoTest give: " + itemId + " x" + count
                        + " 背包满，剩余 " + left.count
                )
            }
        }
        maid.getMaidInventory().setChanged()
    }

    private fun resolveItem(id: String): Item? {
        val ident = Identifier.tryParse(id) ?: return null
        val item = BuiltInRegistries.ITEM.get(ident).map { it.value() }.orElse(null)
        return if (item === Items.AIR) null else item
    }

    /** 位置占位符：{@code {x}} / {@code {x+1}} / {@code {y-2}} …（相对女仆方块坐标，支持加减偏移） */
    private val POS_TOKEN = Pattern.compile("\\{([xyz])([+-]\\d+)?\\}")
    /** 最近一次 setblock 的绝对坐标占位符：{@code {lx}} / {@code {ly+1}} / {@code {lz-2}} … */
    private val LAST_POS_TOKEN = Pattern.compile("\\{l([xyz])([+-]\\d+)?\\}")

    private fun expandPos(maid: SmartMaidEntity, template: String): String {
        val p = maid.blockPosition()
        var s = template
        // {lx}/{ly}/{lz}（可带偏移）：最近一次 run 里 setblock 的坐标（不随女仆移动而变）
        val lm = LAST_POS_TOKEN.matcher(s)
        val lsb = StringBuilder()
        while (lm.find()) {
            val base = lastRunBlock ?: continue
            val delta = lm.group(2)?.toInt() ?: 0
            val value = when (lm.group(1)) {
                "x" -> base.x
                "y" -> base.y
                else -> base.z
            } + delta
            lm.appendReplacement(lsb, value.toString())
        }
        lm.appendTail(lsb)
        s = lsb.toString()
        val m = POS_TOKEN.matcher(s)
        val sb = StringBuilder()
        while (m.find()) {
            val base = when (m.group(1)) {
                "x" -> p.x
                "y" -> p.y
                else -> p.z
            }
            val delta = m.group(2)?.toInt() ?: 0
            m.appendReplacement(sb, (base + delta).toString())
        }
        m.appendTail(sb)
        return sb.toString()
    }

    /** 执行一条命令（server=控制台源；player=真实玩家源，含实际权限上下文） */
    private fun runCommand(
        player: ServerPlayer,
        maid: SmartMaidEntity,
        id: String,
        cmdTemplate: String,
        sourceKind: String,
        expectResult: Int?,
        expectError: Boolean?,
        expectTask: String?,
        expectYaw: Double?,
        yawTolerance: Double
    ) {
        val cmd = expandPos(maid, cmdTemplate)
        // 记录 setblock 坐标，供后续 check:{"at":"lastRun"} 与 {lx}/{ly}/{lz} 引用
        val sb = SETBLOCK_POS.matcher(cmd)
        if (sb.find()) {
            lastRunBlock = BlockPos(
                sb.group(1).toInt(), sb.group(2).toInt(), sb.group(3).toInt()
            )
        }
        val server = maid.level().server
        if (server == null) {
            SmartMaid.LOGGER.info("AutoTest [$id] run: 无服务器")
            return
        }
        val source = if (sourceKind == "player") player.createCommandSourceStack()
        else server.createCommandSourceStack()
        try {
            val r = server.commands.dispatcher.execute(cmd, source)
            val ok = expectResult == null || r == expectResult
            SmartMaid.LOGGER.info("AutoTest [$id] run($sourceKind): $cmd -> $r"
                + (if (expectResult == null) "" else " 期望=$expectResult 断言=" + (if (ok) "PASS" else "FAIL")))
            if (expectError == true) {
                count(false)
                SmartMaid.LOGGER.info("AutoTest [$id] 期望拒绝但执行成功")
            } else if (expectResult != null) {
                count(ok)
            }
            if (expectTask != null) {
                val actual = maid.maidTaskManager.currentTask()?.taskId()
                val taskOk = actual == expectTask
                count(taskOk)
                SmartMaid.LOGGER.info(
                    "AutoTest [$id] 任务断言=" + (if (taskOk) "PASS" else "FAIL")
                        + " 期望=$expectTask 实际=$actual"
                )
            }
            if (expectYaw != null) {
                val actual = maid.yHeadRot.toDouble()
                val yawOk = angleDistance(actual, expectYaw) <= yawTolerance
                count(yawOk)
                SmartMaid.LOGGER.info(
                    "AutoTest [$id] 朝向断言=" + (if (yawOk) "PASS" else "FAIL")
                        + " 期望=$expectYaw±$yawTolerance 实际=$actual"
                )
            }
        } catch (e: CommandSyntaxException) {
            SmartMaid.LOGGER.info("AutoTest [$id] run($sourceKind) 拒绝: $cmd : " + e.message)
            if (expectError == true) {
                count(true)
                SmartMaid.LOGGER.info("AutoTest [$id] 期望拒绝命中 PASS")
            } else if (expectResult != null || expectError != null) {
                count(false)
            }
        } catch (e: Exception) {
            SmartMaid.LOGGER.info("AutoTest [$id] run($sourceKind) 异常: $cmd : " + e)
            if (expectError == true) {
                count(true)
            } else if (expectResult != null || expectError != null) {
                count(false)
            }
        }
    }

    /** 从 run 的 maidtasks move 命令取下目标，供 record 计算水平距离。 */
    private fun parseMoveTarget(maid: SmartMaidEntity, template: String): BlockPos? {
        val m = MOVE_POS.matcher(expandPos(maid, template))
        if (!m.find()) {
            return null
        }
        return BlockPos(
            m.group(1).toDouble().toInt(),
            m.group(2).toDouble().toInt(),
            m.group(3).toDouble().toInt()
        )
    }

    /** 将逐 tick 采样汇总成可断言的几何摘要。 */
    private fun finishRecord(rec: RecordState) {
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var minDist = Double.MAX_VALUE
        var count = 0
        val target = rec.target
        for (line in rec.samples) {
            val p = line.split(',')
            if (p.size < 8) continue
            val x = p[1].toDoubleOrNull() ?: continue
            val y = p[2].toDoubleOrNull() ?: continue
            val z = p[3].toDoubleOrNull() ?: continue
            count++
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
            if (target != null) {
                val dx = x - (target.x + 0.5)
                val dz = z - (target.z + 0.5)
                minDist = minOf(minDist, kotlin.math.sqrt(dx * dx + dz * dz))
            }
        }
        val summary = RecordSummary(
            rec.id,
            count,
            if (count == 0) Double.NaN else minY,
            if (count == 0) Double.NaN else maxY,
            if (minDist == Double.MAX_VALUE) null else minDist,
            target
        )
        LAST_RECORD = summary
        SmartMaid.LOGGER.info(
            "AutoTest [${rec.id}] record-summary samples=$count minY=$minY maxY=$maxY "
                + "minTargetDist=${summary.minTargetDist}"
        )
    }

    /** 轨迹断言：minTargetDistMax / minYMin / maxYMin / maxYMax。 */
    private fun checkRecord(c: JsonObject): Boolean {
        val r = LAST_RECORD
        if (r == null) {
            SmartMaid.LOGGER.info("checkRecord: 尚无 record 数据")
            return false
        }
        var ok = true
        if (c.has("minTargetDistMax")) {
            val max = c.get("minTargetDistMax").asDouble
            ok = ok && r.minTargetDist != null && r.minTargetDist <= max
        }
        if (c.has("minYMin")) {
            ok = ok && r.minY >= c.get("minYMin").asDouble
        }
        if (c.has("maxYMin")) {
            ok = ok && r.maxY >= c.get("maxYMin").asDouble
        }
        if (c.has("maxYMax")) {
            ok = ok && r.maxY <= c.get("maxYMax").asDouble
        }
        SmartMaid.LOGGER.info(
            "checkRecord id=${r.id} samples=${r.samples} minY=${r.minY} maxY=${r.maxY} "
                + "minTargetDist=${r.minTargetDist} 期望=$c -> $ok"
        )
        return ok
    }

    /** 断言计数（结束输出汇总用） */
    private fun count(ok: Boolean) {
        if (ok) {
            PASS_COUNT++
        } else {
            FAIL_COUNT++
        }
    }

    /**
     * 执行一条检查断言（AutoTest 专用，不产生指令回执）。支持的类型：
     *
     * <pre>{@code
     * {"check": {"block":  {"offset":[1,-1,0], "is":"minecraft:air"}}}       // 女仆脚下偏移处（或 pos:[x,y,z]）
     * {"check": {"inv":    {"item":"minecraft:dirt", "min":1}}}              // 背包（0-35）中该物品总数
     * {"check": {"freeSlots": {"min":1}}}                                      // 背包区空槽数
     * {"check": {"bridge": {"item":"minecraft:diamond_block","allowed":false}}} // 搭路白名单判定
     * {"check": {"tag":    {"tag":"smartmaid:never_target","entity":"minecraft:enderman","contains":true}}}
     * {"check": {"record": {"minTargetDistMax":2.0,"minYMin":65.9}}}            // 最近一次 record 的几何摘要
     * }</pre>
     */
    private fun runCheck(maid: SmartMaidEntity, check: JsonObject): Boolean {
        try {
            if (check.has("block")) {
                return checkBlock(maid, check.getAsJsonObject("block"))
            }
            if (check.has("inv")) {
                return checkInv(maid, check.getAsJsonObject("inv"))
            }
            if (check.has("bridge")) {
                return checkBridge(check.getAsJsonObject("bridge"))
            }
            if (check.has("tag")) {
                return checkTag(check.getAsJsonObject("tag"))
            }
            if (check.has("freeSlots")) {
                return checkFreeSlots(maid, check.getAsJsonObject("freeSlots"))
            }
            if (check.has("slot")) {
                return checkSlot(maid, check.getAsJsonObject("slot"))
            }
            if (check.has("container")) {
                return checkContainer(maid, check.getAsJsonObject("container"))
            }
            if (check.has("look")) {
                return checkLook(maid, check.getAsJsonObject("look"))
            }
            if (check.has("task")) {
                return checkTask(maid, check.getAsJsonObject("task"))
            }
            if (check.has("record")) {
                return checkRecord(check.getAsJsonObject("record"))
            }
        } catch (e: Exception) {
            SmartMaid.LOGGER.info("AutoTest check 异常: $e")
            return false
        }
        SmartMaid.LOGGER.info("AutoTest check: 未识别的检查类型 $check")
        return false
    }

    /** 方块状态断言：{@code is} 为期望的方块 id（缺省只要求"能读到"） */
    private fun checkBlock(maid: SmartMaidEntity, c: JsonObject): Boolean {
        val pos = posOf(maid, c)
        if (pos == null) {
            SmartMaid.LOGGER.info("checkBlock: 缺少 pos 或 offset")
            return false
        }
        val state: BlockState = maid.level().getBlockState(pos)
        val actual = BuiltInRegistries.BLOCK.getKey(state.block)
        val want = if (c.has("is")) c.get("is").asString else null
        val ok = want == null || want == actual.toString()
        SmartMaid.LOGGER.info("checkBlock $pos 实际=$actual 期望=$want -> $ok")
        return ok
    }

    private fun posOf(maid: SmartMaidEntity, c: JsonObject): BlockPos? {
        // at:lastRun —— 用最近一次 run 里 setblock 的绝对坐标（女仆移动也不影响断言）
        if (c.has("at") && "lastRun" == c.get("at").asString) {
            if (lastRunBlock == null) {
                SmartMaid.LOGGER.info("AutoTest checkBlock: at=lastRun 但还没有 run 过 setblock")
            }
            val base = lastRunBlock ?: return null
            if (c.has("offset") && c.get("offset").isJsonArray) {
                val a = c.getAsJsonArray("offset")
                return base.offset(a.get(0).asInt, a.get(1).asInt, a.get(2).asInt)
            }
            return base
        }
        if (c.has("offset") && c.get("offset").isJsonArray) {
            val a = c.getAsJsonArray("offset")
            return maid.blockPosition().offset(
                a.get(0).asInt, a.get(1).asInt, a.get(2).asInt
            )
        }
        if (c.has("pos") && c.get("pos").isJsonArray) {
            val a = c.getAsJsonArray("pos")
            return BlockPos(a.get(0).asInt, a.get(1).asInt, a.get(2).asInt)
        }
        return null
    }

    /** 背包数量断言：{@code min} / {@code max} / {@code is}（三者可任选） */
    private fun checkInv(maid: SmartMaidEntity, c: JsonObject): Boolean {
        val itemId = c.get("item").asString
        val item = resolveItem(itemId)
        if (item == null) {
            SmartMaid.LOGGER.info("checkInv: 未找到物品 $itemId")
            return false
        }
        var total = 0
        for (i in 0 until 36) {
            val s = maid.getMaidInventory().getItem(i)
            if (s.`is`(item)) {
                total += s.count
            }
        }
        var ok = true
        if (c.has("min")) {
            ok = ok && total >= c.get("min").asInt
        }
        if (c.has("max")) {
            ok = ok && total <= c.get("max").asInt
        }
        if (c.has("is")) {
            ok = ok && total == c.get("is").asInt
        }
        SmartMaid.LOGGER.info("checkInv $itemId 实际=$total -> $ok")
        return ok
    }

    /** 女仆单槽断言（0-40）：{@code empty} / {@code item} / {@code count} / {@code min} / {@code max}。 */
    private fun checkSlot(maid: SmartMaidEntity, c: JsonObject): Boolean {
        val idx = if (c.has("slot")) c.get("slot").asInt else -1
        val inv = maid.getMaidInventory()
        if (idx < 0 || idx >= inv.containerSize) {
            SmartMaid.LOGGER.info("checkSlot: 非法槽位 $idx（有效 0-" + (inv.containerSize - 1) + "）")
            return false
        }
        val stack = inv.getItem(idx)
        val ok = matchStack(stack, c)
        SmartMaid.LOGGER.info(
            "checkSlot #$idx 实际=" + itemName(stack) + " x" + stack.count + " 期望=$c -> $ok"
        )
        return ok
    }

    /** 容器方块单槽断言；位置支持 {@code at:lastRun} / {@code pos} / {@code offset}。 */
    private fun checkContainer(maid: SmartMaidEntity, c: JsonObject): Boolean {
        val pos = posOf(maid, c)
        if (pos == null) {
            SmartMaid.LOGGER.info("checkContainer: 缺少 pos / offset / at=lastRun")
            return false
        }
        val container = maid.level().getBlockEntity(pos) as? Container
        if (container == null) {
            SmartMaid.LOGGER.info("checkContainer: $pos 不是容器方块实体")
            return false
        }
        val idx = if (c.has("slot")) c.get("slot").asInt else 0
        if (idx < 0 || idx >= container.containerSize) {
            SmartMaid.LOGGER.info("checkContainer: 非法槽位 $idx（有效 0-" + (container.containerSize - 1) + "）")
            return false
        }
        val stack = container.getItem(idx)
        val ok = matchStack(stack, c)
        SmartMaid.LOGGER.info(
            "checkContainer $pos #$idx 实际=" + itemName(stack) + " x" + stack.count + " 期望=$c -> $ok"
        )
        return ok
    }

    /** 朝向断言：yaw/pitch 为角度，允许 {@code tolerance}（默认 2°）。 */
    private fun checkLook(maid: SmartMaidEntity, c: JsonObject): Boolean {
        val tolerance = if (c.has("tolerance")) c.get("tolerance").asDouble else 2.0
        val useBody = c.has("part") && c.get("part").asString.equals("body", ignoreCase = true)
        val yaw = if (useBody) maid.yRot.toDouble() else maid.yHeadRot.toDouble()
        val pitch = maid.xRot.toDouble()
        var ok = true
        if (c.has("yaw")) {
            ok = ok && angleDistance(yaw, c.get("yaw").asDouble) <= tolerance
        }
        if (c.has("pitch")) {
            ok = ok && angleDistance(pitch, c.get("pitch").asDouble) <= tolerance
        }
        SmartMaid.LOGGER.info(
            "checkLook part=" + (if (useBody) "body" else "head")
                + " yaw=" + yaw + " pitch=" + pitch + " 期望=$c -> $ok"
        )
        return ok
    }

    /** 当前任务/队列规模断言：{@code current}（null 表示无任务）/ {@code shortSize} / {@code longSize}。 */
    private fun checkTask(maid: SmartMaidEntity, c: JsonObject): Boolean {
        val manager = maid.maidTaskManager
        var ok = true
        if (c.has("current")) {
            val actual = manager.currentTask()?.taskId()
            val want = if (c.get("current").isJsonNull) null else c.get("current").asString
            ok = ok && actual == want
            SmartMaid.LOGGER.info("checkTask current 实际=$actual 期望=$want")
        }
        if (c.has("shortSize")) {
            val actual = manager.queue().shortSize()
            ok = ok && actual == c.get("shortSize").asInt
            SmartMaid.LOGGER.info("checkTask shortSize 实际=$actual 期望=" + c.get("shortSize").asInt)
        }
        if (c.has("longSize")) {
            val actual = manager.queue().longSize()
            ok = ok && actual == c.get("longSize").asInt
            SmartMaid.LOGGER.info("checkTask longSize 实际=$actual 期望=" + c.get("longSize").asInt)
        }
        return ok
    }

    private fun matchStack(stack: ItemStack, c: JsonObject): Boolean {
        var ok = true
        if (c.has("empty")) {
            ok = ok && (stack.isEmpty == c.get("empty").asBoolean)
        }
        if (c.has("item")) {
            val item = resolveItem(c.get("item").asString) ?: return false
            ok = ok && stack.`is`(item)
        }
        if (c.has("count")) {
            ok = ok && stack.count == c.get("count").asInt
        }
        if (c.has("min")) {
            ok = ok && stack.count >= c.get("min").asInt
        }
        if (c.has("max")) {
            ok = ok && stack.count <= c.get("max").asInt
        }
        return ok
    }

    private fun itemName(stack: ItemStack): String =
        if (stack.isEmpty) "empty" else BuiltInRegistries.ITEM.getKey(stack.item).toString()

    private fun angleDistance(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return kotlin.math.abs(d)
    }

    /** 背包区（0-35）空槽数 */
    private fun freeSlots(maid: SmartMaidEntity): Int {
        var n = 0
        for (i in 0 until 36) {
            if (maid.getMaidInventory().getItem(i).isEmpty) {
                n++
            }
        }
        return n
    }

    /** 空槽数断言：{@code min} / {@code max} / {@code is}（三者可任选） */
    private fun checkFreeSlots(maid: SmartMaidEntity, c: JsonObject): Boolean {
        val free = freeSlots(maid)
        var ok = true
        if (c.has("min")) {
            ok = ok && free >= c.get("min").asInt
        }
        if (c.has("max")) {
            ok = ok && free <= c.get("max").asInt
        }
        if (c.has("is")) {
            ok = ok && free == c.get("is").asInt
        }
        SmartMaid.LOGGER.info("checkFreeSlots 空槽=$free 期望=$c -> $ok")
        return ok
    }

    /** 搭路白名单断言：断言某物品是否被判定为"可消耗的建材" */
    private fun checkBridge(c: JsonObject): Boolean {
        val itemId = c.get("item").asString
        val item = resolveItem(itemId)
        if (item == null) {
            SmartMaid.LOGGER.info("checkBridge: 未找到物品 $itemId")
            return false
        }
        val allowed = MaidActions.isBridgeBlock(ItemStack(item))
        val want = !c.has("allowed") || c.get("allowed").asBoolean
        SmartMaid.LOGGER.info("checkBridge $itemId allowed=$allowed 期望=$want")
        return allowed == want
    }

    /** 数据包 tag 断言：验证 tag 文件真的生效（如 #smartmaid:never_target 是否含末影人） */
    private fun checkTag(c: JsonObject): Boolean {
        val tagId = Identifier.tryParse(c.get("tag").asString)
        val entityId = Identifier.tryParse(c.get("entity").asString)
        if (tagId == null || entityId == null) {
            SmartMaid.LOGGER.info("checkTag: tag/entity id 非法")
            return false
        }
        val type = BuiltInRegistries.ENTITY_TYPE.get(entityId).map { it.value() }.orElse(null)
        if (type == null) {
            SmartMaid.LOGGER.info("checkTag: 未找到实体 $entityId")
            return false
        }
        val tag: TagKey<EntityType<*>> = TagKey.create(Registries.ENTITY_TYPE, tagId)
        val inTag = type.builtInRegistryHolder().`is`(tag)
        val want = !c.has("contains") || c.get("contains").asBoolean
        SmartMaid.LOGGER.info("checkTag $entityId in $tagId = $inTag 期望=$want")
        return inTag == want
    }

    /**
     * 断言回执：expect 为扁平 JSON，键是点分路径（如 {@code ok} / {@code result.craftable}），
     * 值是与回执对应字段的期望值；全部匹配返回 true。
     */
    private fun checkExpect(expect: JsonObject, result: MaidCommandResult): Boolean {
        val actual: JsonObject
        try {
            actual = JsonParser.parseString(result.toJson()).asJsonObject
        } catch (e: Exception) {
            return false
        }
        for ((key, expected) in expect.entrySet()) {
            val got = getByPath(actual, key)
            if (got == null || !jsonEquals(got, expected)) {
                SmartMaid.LOGGER.info(
                    "AutoTest 断言失败: " + key
                        + " 期望=" + expected + " 实际=" + (got ?: "null")
                )
                return false
            }
        }
        return true
    }

    private fun getByPath(root: JsonObject, path: String): JsonElement? {
        var cur: JsonElement? = root
        for (part in path.split('.')) {
            val obj = cur
            if (obj == null || !obj.isJsonObject) {
                return null
            }
            cur = obj.asJsonObject.get(part)
        }
        return cur
    }

    private fun jsonEquals(got: JsonElement, expected: JsonElement): Boolean {
        if (got.isJsonPrimitive && expected.isJsonPrimitive) {
            val g: JsonPrimitive = got.asJsonPrimitive
            val e: JsonPrimitive = expected.asJsonPrimitive
            if (g.isBoolean || e.isBoolean) {
                return g.isBoolean && e.isBoolean && g.asBoolean == e.asBoolean
            }
            if (g.isNumber && e.isNumber) {
                return g.asBigDecimal.compareTo(e.asBigDecimal) == 0
            }
            return g.asString == e.asString
        }
        return got == expected
    }

    private fun findMaid(player: ServerPlayer): SmartMaidEntity? {
        val level = player.level()
        for (entity in level.allEntities) {
            if (entity is SmartMaidEntity && entity.isOwnedBy(player)) {
                return entity
            }
        }
        return null
    }
}
