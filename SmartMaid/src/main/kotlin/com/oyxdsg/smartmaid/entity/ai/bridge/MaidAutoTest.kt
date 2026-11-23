package com.oyxdsg.smartmaid.entity.ai.bridge

import com.google.gson.*
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

    private const val STEP_TICKS = 10          // 状态机推进间隔
    private const val TASK_WAIT_TICKS = 40     // 任务型指令后等待（≈2 秒）

    /** 由 ServerTickEvents.END_SERVER_TICK 注册调用（仅服务端触发） */
    @JvmStatic
    fun onServerTick(server: MinecraftServer) {
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
        run(maid)
    }

    /** 状态机：每 STEP_TICKS tick 推进一条；任务型回执后等 TASK_WAIT_TICKS。 */
    private fun run(maid: SmartMaidEntity) {
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
            && !req.has("tidy") && !req.has("vetoNextBreak") && !req.has("expect")
        ) {
            return
        }

        // vetoNextBreak：武装"测试否决器"，用于验证破坏事件链（见静态块）
        if (req.has("vetoNextBreak")) {
            vetoNextBreak = req.get("vetoNextBreak").asBoolean
            SmartMaid.LOGGER.info("AutoTest [$id] vetoNextBreak=$vetoNextBreak")
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

        // run：执行一条命令（测试准备用，如 setblock 造环境）。
        // 用服务器控制台源执行（权限最高），{x}/{y}/{z} 会替换为女仆方块坐标。
        if (req.has("run")) {
            val cmd = req.get("run").asString
            runCommand(maid, id, cmd)
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

    private fun expandPos(maid: SmartMaidEntity, template: String): String {
        val p = maid.blockPosition()
        var s = template
        // {lx}/{ly}/{lz}：最近一次 run 里 setblock 的坐标（不随女仆移动而变）
        lastRunBlock?.let { last ->
            s = s.replace("{lx}", last.x.toString())
                .replace("{ly}", last.y.toString())
                .replace("{lz}", last.z.toString())
        }
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

    /** 执行一条命令（服务器控制台源，权限最高；位置占位符见 {@link #expandPos}） */
    private fun runCommand(maid: SmartMaidEntity, id: String, cmdTemplate: String) {
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
        try {
            val r = server.commands.dispatcher.execute(cmd, server.createCommandSourceStack())
            SmartMaid.LOGGER.info("AutoTest [$id] run: $cmd -> $r")
        } catch (e: Exception) {
            SmartMaid.LOGGER.info("AutoTest [$id] run 失败: $cmd : " + e.message)
        }
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
            return lastRunBlock
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
