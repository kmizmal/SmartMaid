package com.oyxdsg.smartmaid.client.test

import com.google.gson.*
import com.oyxdsg.smartmaid.client.SmartMaidClient
import com.oyxdsg.smartmaid.client.gui.MaidInventoryScreen
import com.oyxdsg.smartmaid.client.gui.MaidMenuScreen
import com.oyxdsg.smartmaid.client.gui.MaidSubScreen
import com.oyxdsg.smartmaid.compat.MaidCompat
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.test.MaidErrorSink
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*

/**
 * 客户端自动化测试运行器 —— **文件驱动，全程不碰真实鼠标/键盘**。
 *
 * <h2>为什么需要它</h2>
 * 既有的 `MaidAutoTest` 跑在**服务端**线程，覆盖不到 GUI 与键位 —— 而 26.3 迁移中
 * 恰恰是这两处出问题（GLFW→SDL 导致键码全变、Screen 打不开）。本运行器补上客户端侧。
 *
 * <h2>怎么用</h2>
 * 把 `tools/clienttest.<名字>.json` 复制成
 * `<gameDir>/config/smartmaid/clienttest.json`，进游戏（要有世界）即可自动跑；
 * 跑完写 `clienttest.report.json`，并把输入文件改名成 `clienttest.done.json`。
 * 也可用 `python tools/clienttest.py run <名字>` 一条命令完成放置 + 结果读取。
 *
 * <h2>支持的 op</h2>
 * <pre>
 *   wait              {"ticks":20}                         等待若干 tick
 *   assertKeyMapping  {"expect":"key.keyboard.e"}          断言 OPEN_INVENTORY 注册的键（默认键）
 *   pollKeys          {"ticks":60,"probe":["shift","e"]}   采样真实按键状态（验证编码是否正确）
 *   openMenu          {"maidId":-1,"food":20}              直接打开女仆主菜单（等价于收到 S2C 包）
 *   clickEntry        {"index":0}                          在 MaidMenuScreen 上点第 i 个入口
 *   clickAction       {"index":0}                          在 MaidMenuScreen 上点第 i 个高频动作
 *   clickAt           {"x":100,"y":80}                     在任意位置点一下（命中测试用）
 *   clickBack         {}                                   点二级页的「返回」
 *   assertScreen      {"expect":"MaidTaskScreen"}          断言当前 Screen 的简单类名
 *   invokeOpenInventory {}                                 直接跑「Shift+E 开背包」的处理逻辑
 *   closeScreen       {}                                   关闭当前 Screen
 *   sendCommand       {"command":"summonmaid"}             以真实玩家身份发送服务器命令
 *   waitForScreen     {"expect":"MaidInventoryScreen","ticks":100}  等待 Screen（超时失败）
 *   waitForMaid       {"min":0,"ticks":100}                等待最近女仆 id 达标（超时失败）
 *   assertNearestMaid {"min":0}                            断言附近有女仆实体
 *   assertMenuMaid    {"expect":"nearest"}                 断言 MaidInventoryMenu 的 maidId
 *   assertSlot        {"slot":5,"item":"minecraft:diamond","count":5} 断言容器槽位
 *   assertMenuTotal   {"item":"minecraft:diamond","count":5}          断言容器内物品总数
 *   clickSlot         {"slot":5,"clickType":"QUICK_MOVE"}  走真实容器点击链路（含 shift-click）
 *   assertMaidEquipment {"slot":"head","item":"minecraft:iron_helmet"} 断言女仆装备同步
 *   note              {"msg":"..."}                        写一条信息进报告
 * </pre>
 *
 * <h2>安全约束（重要）</h2>
 * <ol>
 *   <li>**任何异常都在本类内被吞掉并上报**，绝不让测试把游戏搞崩；</li>
 *   <li>不注入真实输入设备事件，只调用 Screen 的公开输入回调（`mouseClicked` 等）；</li>
 *   <li>没放测试文件时，[tick] 是一次目录存在性检查，零开销。</li>
 * </ol>
 */
object MaidClientTest {

    private const val IN_NAME = "clienttest.json"
    private const val REPORT_NAME = "clienttest.report.json"
    private const val DONE_NAME = "clienttest.done.json"

    private var cases: MutableList<JsonObject>? = null
    private var results: JsonArray? = null
    private var testName: String = "(unnamed)"
    private var index: Int = 0
    private var waitTicks: Int = 0
    private var finished: Boolean = false
    private lateinit var inFile: Path
    private lateinit var reportFile: Path
    private lateinit var doneFile: Path
    private var startedAt: Long = 0L

    /** 连续按键采样状态（pollKeys 用）。 */
    private var pollTicks: Int = 0
    private val pollSeen: MutableMap<String, Boolean> = HashMap()

    /** waitForScreen / waitForMaid 的等待状态。 */
    private var waitCheck: WaitCheck? = null

    private var waitingNoted: Boolean = false

    private val GSON: Gson = GsonBuilder()
        .setPrettyPrinting().disableHtmlEscaping().create()

    private fun configDir(): Path {
        return MaidErrorSink.gameDir().resolve("config").resolve("smartmaid")
    }

    /** 每客户端 tick 调用一次。没放测试文件时是廉价的空操作。 */
    @JvmStatic
    fun tick(mc: Minecraft?) {
        if (finished || mc == null) {
            return
        }
        try {
            // ⚠️ **必须已在世界中**才能跑。原因不是洁癖：本运行器会开 Screen，
            //    而 Screen 会被 Gui 持续 tick，其中有些逻辑要发包 —— 在标题画面/断线状态下
            //    发包会让 Fabric 抛 "Cannot send packets when not in game!"，直接把游戏崩掉。
            //    （实测踩过：脚本在标题画面就跑了 openMenu，20 tick 后崩溃。）
            if (!inGame(mc)) {
                noteWaitingOnce()
                return
            }
            if (cases == null && !load()) {
                return
            }
            waitCheck?.let { wc ->
                if (waitSatisfied(mc, wc)) {
                    wc.result.detail = "等待命中: " + waitDetail(mc, wc)
                    addResult(wc.result)
                    waitCheck = null
                } else if (--wc.remaining <= 0) {
                    wc.result.fail("等待超时: 期望 " + waitDetail(mc, wc) + "，实际 " + waitActual(mc, wc))
                    addResult(wc.result)
                    waitCheck = null
                }
                return
            }
            if (waitTicks > 0) {
                waitTicks--
                return
            }
            if (pollTicks > 0) {
                samplePoll(mc)
                return
            }
            val caseList = cases ?: return
            if (index >= caseList.size) {
                finish()
                return
            }
            runCase(mc, caseList[index++])
        } catch (t: Throwable) {
            MaidErrorSink.report("clienttest/lifecycle", "测试运行器自身异常", t)
            addResult(Result("(runner)", "tick", "ERROR").error(t))
            finish()
        }
    }

    // ------------------------------------------------------------------ 加载 / 收尾

    /** 是否已经在世界里（有连接 + 有客户端关卡）。测试只在此条件下运行。 */
    @JvmStatic
    fun inGame(mc: Minecraft?): Boolean {
        return mc?.level != null && mc.connection != null
    }

    private fun noteWaitingOnce() {
        if (waitingNoted || !Files.isRegularFile(configDir().resolve(IN_NAME))) {
            return
        }
        waitingNoted = true
        MaidErrorSink.note(
            "clienttest",
            "检测到 " + IN_NAME + "，但当前不在世界中 —— 进世界后会自动开始（这是有意为之：" +
                "标题画面下开 Screen 会因发包而崩溃）"
        )
    }

    private fun load(): Boolean {
        val dir = configDir()
        inFile = dir.resolve(IN_NAME)
        reportFile = dir.resolve(REPORT_NAME)
        doneFile = dir.resolve(DONE_NAME)
        if (!Files.isRegularFile(inFile)) {
            finished = true            // 没有测试文件 → 本会话不再检查
            return false
        }
        try {
            val raw = Files.readString(inFile, StandardCharsets.UTF_8)
            val root = JsonParser.parseString(raw).asJsonObject
            testName = if (root.has("name")) root.get("name").asString else "(unnamed)"
            val list = ArrayList<JsonObject>()
            for (e in root.getAsJsonArray("cases")) {
                list.add(e.asJsonObject)
            }
            cases = list
            results = JsonArray()
            index = 0
            waitTicks = 0
            startedAt = System.currentTimeMillis()
            MaidErrorSink.note("clienttest", "开始客户端测试: " + testName + "（" + list.size + " 个用例）")
            return true
        } catch (t: Throwable) {
            MaidErrorSink.report("clienttest/load", "测试脚本解析失败: " + inFile, t)
            cases = ArrayList()
            results = JsonArray()
            addResult(Result("(load)", "parse", "ERROR").error(t))
            finish()
            return false
        }
    }

    private fun finish() {
        if (finished) {
            return
        }
        finished = true
        try {
            val out = JsonObject()
            out.addProperty("name", testName)
            out.addProperty("side", "client")
            out.addProperty("minecraft", MaidErrorSink.mcVersion())
            out.addProperty("modVersion", MaidErrorSink.modVersion())
            out.addProperty("startedAt", java.time.Instant.ofEpochMilli(startedAt).toString())
            out.addProperty("finishedAt", java.time.Instant.now().toString())
            out.addProperty("elapsedMs", System.currentTimeMillis() - startedAt)
            var pass = 0
            var fail = 0
            var error = 0
            val resultList = results!!
            for (e in resultList) {
                val s = e.asJsonObject.get("status").asString
                when (s) {
                    "PASS" -> pass++
                    "FAIL" -> fail++
                    else -> error++
                }
            }
            val sum = JsonObject()
            sum.addProperty("total", resultList.size())
            sum.addProperty("pass", pass)
            sum.addProperty("fail", fail)
            sum.addProperty("error", error)
            sum.addProperty("verdict", if (error > 0) "ERROR" else (if (fail > 0) "FAIL" else "PASS"))
            out.add("summary", sum)
            out.add("cases", resultList)
            out.add("env", env(Minecraft.getInstance()))
            Files.createDirectories(reportFile.parent)
            Files.writeString(reportFile, GSON.toJson(out), StandardCharsets.UTF_8)
            if (Files.isRegularFile(inFile)) {
                Files.move(inFile, doneFile, StandardCopyOption.REPLACE_EXISTING)
            }
            MaidErrorSink.note(
                "clienttest", String.format(
                    "客户端测试结束: %s（%d 用例：PASS=%d FAIL=%d ERROR=%d）→ %s",
                    testName, resultList.size(), pass, fail, error, reportFile
                )
            )
        } catch (e: IOException) {
            MaidErrorSink.report("clienttest/finish", "测试报告写入失败", e)
        }
    }

    // ------------------------------------------------------------------ 用例执行

    private fun runCase(mc: Minecraft, c: JsonObject) {
        val op = str(c, "op", "?")!!
        val id = str(c, "id", op + "#" + index)!!
        val r = Result(id, op, "PASS")
        var deferred = false
        try {
            when (op) {
                "wait" -> {
                    waitTicks = num(c, "ticks", 20)
                    r.detail = "等待 " + waitTicks + " tick"
                }
                "note" -> r.detail = str(c, "msg", "")
                "assertKeyMapping" -> assertKeyMapping(mc, c, r)
                "pollKeys" -> startPoll(c, r)
                "openMenu" -> {
                    val maidId = num(c, "maidId", nearestMaidId(mc))
                    val food = num(c, "food", 20)
                    mc.setScreenAndShow(MaidMenuScreen(maidId, food))
                    r.detail = "setScreenAndShow(MaidMenuScreen maidId=" + maidId + " food=" + food + ")"
                }
                "clickEntry" -> {
                    val m = mc.gui.screen() as? MaidMenuScreen
                    if (m == null) {
                        r.fail("当前不是 MaidMenuScreen，而是 " + screenName(mc))
                    } else {
                        val i = num(c, "index", 0)
                        val rect = m.entryRectForTest(i)
                        r.detail = clickAt(mc, rect, i, "入口")
                        if (r.detail == null) {
                            r.fail("入口索引越界: " + i)
                        }
                    }
                }
                "clickAction" -> {
                    val m = mc.gui.screen() as? MaidMenuScreen
                    if (m == null) {
                        r.fail("当前不是 MaidMenuScreen，而是 " + screenName(mc))
                    } else {
                        val i = num(c, "index", 0)
                        val rect = m.actionRectForTest(i)
                        r.detail = clickAt(mc, rect, i, "动作")
                        if (r.detail == null) {
                            r.fail("动作索引越界: " + i)
                        }
                    }
                }
                "clickBack" -> {
                    val sub = mc.gui.screen() as? MaidSubScreen
                    if (sub == null) {
                        r.fail("当前不是二级页，而是 " + screenName(mc))
                    } else {
                        r.detail = clickAt(mc, sub.backRectForTest(), 0, "返回")
                    }
                }
                "clickAt" -> {
                    val sc = mc.gui.screen()
                    if (sc == null) {
                        r.fail("当前没有打开的 Screen")
                    } else {
                        val x = c.get("x").asDouble
                        val y = c.get("y").asDouble
                        val handled = sc.mouseClicked(
                            MouseButtonEvent(x, y, MouseButtonInfo(MaidCompat.primaryMouseButton(), 0)), false
                        )
                        r.detail = String.format("clickAt(%.0f,%.0f) handled=%s", x, y, handled)
                    }
                }
                "assertScreen" -> {
                    val expect = str(c, "expect", "")!!
                    val actual = screenName(mc)
                    if (expect == actual) {
                        r.detail = "当前 Screen = " + actual
                    } else {
                        r.fail("期望 Screen=" + expect + "，实际=" + actual)
                    }
                }
                "invokeOpenInventory" -> {
                    val handled = SmartMaidClient.tryOpenMaidInventory()
                    r.detail = "tryOpenMaidInventory() = " + handled +
                        "（false 通常表示此刻没有真的按住 Shift —— 这是输入状态，不是缺陷）"
                }
                "closeScreen" -> {
                    val sc = mc.gui.screen()
                    if (sc == null) {
                        r.detail = "本就没有 Screen"
                    } else {
                        sc.onClose()
                        r.detail = "已关闭 " + sc.javaClass.simpleName
                    }
                }
                "sendCommand" -> sendCommand(mc, c, r)
                "waitForScreen" -> {
                    startWait(mc, c, r, "screen")
                    deferred = true
                }
                "waitForMaid" -> {
                    startWait(mc, c, r, "maid")
                    deferred = true
                }
                "assertNearestMaid" -> assertNearestMaid(mc, c, r)
                "assertMenuMaid" -> assertMenuMaid(mc, c, r)
                "assertSlot" -> assertSlot(mc, c, r)
                "assertMenuTotal" -> assertMenuTotal(mc, c, r)
                "clickSlot" -> clickSlot(mc, c, r)
                "assertMaidEquipment" -> assertMaidEquipment(mc, c, r)
                else -> r.fail("未知 op: " + op)
            }
        } catch (t: Throwable) {
            r.error(t)
        }
        if (r.detail == null) {
            r.detail = ""
        }
        if (!deferred && pollTicks <= 0) {
            addResult(r)
        }
    }

    private fun assertKeyMapping(mc: Minecraft, c: JsonObject, r: Result) {
        val km = SmartMaidClient.OPEN_INVENTORY
        val key = km.defaultKey
        val actualName = key.name
        val actualValue = key.value
        val actualType = key.type.toString()
        val expect = str(c, "expect", null)
        r.detail = String.format("name=%s value=%d type=%s", actualName, actualValue, actualType)
        if (expect != null && expect != actualName) {
            r.fail("期望键名 " + expect + "，实际 " + actualName + "（value=" + actualValue + "）")
        }
    }

    private fun sendCommand(mc: Minecraft, c: JsonObject, r: Result) {
        val command = str(c, "command", "")!!.trim().removePrefix("/")
        if (command.isEmpty()) {
            r.fail("command 为空")
            return
        }
        val connection = mc.player?.connection
        if (connection == null) {
            r.fail("客户端尚未连接")
            return
        }
        connection.sendCommand(command)
        r.detail = "sendCommand: $command"
    }

    private fun startWait(mc: Minecraft, c: JsonObject, r: Result, kind: String) {
        val ticks = num(c, "ticks", 100).coerceAtLeast(1)
        waitCheck = WaitCheck(kind, str(c, "expect", null), num(c, "min", 0), ticks, r)
        r.detail = "等待 " + waitDetail(mc, waitCheck!!)
    }

    private fun waitSatisfied(mc: Minecraft, wc: WaitCheck): Boolean {
        return when (wc.kind) {
            "screen" -> screenName(mc) == wc.expect
            "maid" -> nearestMaidId(mc) >= wc.min
            else -> false
        }
    }

    private fun waitActual(mc: Minecraft, wc: WaitCheck): String {
        return when (wc.kind) {
            "screen" -> "Screen=" + screenName(mc)
            "maid" -> "nearestMaidId=" + nearestMaidId(mc)
            else -> "?"
        }
    }

    private fun waitDetail(mc: Minecraft, wc: WaitCheck): String {
        return when (wc.kind) {
            "screen" -> "Screen=" + wc.expect + "（最多 " + wc.remaining + " tick）"
            "maid" -> "nearestMaidId>=" + wc.min + "（最多 " + wc.remaining + " tick）"
            else -> "?"
        }
    }

    private fun assertNearestMaid(mc: Minecraft, c: JsonObject, r: Result) {
        val actual = nearestMaidId(mc)
        val min = num(c, "min", 0)
        if (actual < min) {
            r.fail("期望最近女仆 id >= $min，实际=$actual")
        } else {
            r.detail = "nearestMaidId=$actual"
        }
    }

    private fun assertMenuMaid(mc: Minecraft, c: JsonObject, r: Result) {
        val screen = mc.gui.screen() as? MaidInventoryScreen
        if (screen == null) {
            r.fail("当前不是 MaidInventoryScreen，而是 " + screenName(mc))
            return
        }
        val actual = screen.menu.getMaidId()
        val expectText = str(c, "expect", "nearest")!!
        val expected = if (expectText == "nearest") nearestMaidId(mc) else expectText.toIntOrNull()
        if (expected == null || actual != expected) {
            r.fail("菜单 maidId 期望=$expected（$expectText），实际=$actual")
        } else {
            r.detail = "MaidInventoryMenu maidId=$actual"
        }
    }

    private fun assertSlot(mc: Minecraft, c: JsonObject, r: Result) {
        val screen = mc.gui.screen() as? MaidInventoryScreen
        if (screen == null) {
            r.fail("当前不是 MaidInventoryScreen，而是 " + screenName(mc))
            return
        }
        val idx = num(c, "slot", -1)
        if (idx !in screen.menu.slots.indices) {
            r.fail("槽位越界: $idx（0-" + (screen.menu.slots.size - 1) + "）")
            return
        }
        val stack = screen.menu.slots[idx].item
        val ok = matchClientStack(stack, c)
        if (!ok) {
            r.fail("槽位 $idx 实际=" + clientItemName(stack) + " x" + stack.count + "，期望=" + c)
        } else {
            r.detail = "槽位 $idx = " + clientItemName(stack) + " x" + stack.count
        }
    }

    private fun assertMenuTotal(mc: Minecraft, c: JsonObject, r: Result) {
        val screen = mc.gui.screen() as? MaidInventoryScreen
        if (screen == null) {
            r.fail("当前不是 MaidInventoryScreen，而是 " + screenName(mc))
            return
        }
        val itemId = str(c, "item", "")!!
        val want = num(c, "count", -1)
        var total = 0
        for (slot in screen.menu.slots) {
            val stack = slot.item
            if (!stack.isEmpty && clientItemName(stack) == itemId) {
                total += stack.count
            }
        }
        if (total != want) {
            r.fail("$itemId 总数期望=$want，实际=$total")
        } else {
            r.detail = "$itemId 总数=$total"
        }
    }

    private fun clickSlot(mc: Minecraft, c: JsonObject, r: Result) {
        val screen = mc.gui.screen() as? MaidInventoryScreen
        if (screen == null) {
            r.fail("当前不是 MaidInventoryScreen，而是 " + screenName(mc))
            return
        }
        val idx = num(c, "slot", -1)
        if (idx !in screen.menu.slots.indices) {
            r.fail("槽位越界: $idx（0-" + (screen.menu.slots.size - 1) + "）")
            return
        }
        val button = num(c, "button", 0)
        val clickType = str(c, "clickType", "PICKUP")!!.uppercase()
        val input = try {
            ContainerInput.valueOf(clickType)
        } catch (_: IllegalArgumentException) {
            r.fail("未知 clickType: $clickType")
            return
        }
        val player = mc.player
        if (player == null) {
            r.fail("player 为空")
            return
        }
        val gameMode = mc.gameMode
        if (gameMode == null) {
            r.fail("gameMode 为空")
            return
        }
        gameMode.handleContainerInput(screen.menu.containerId, idx, button, input, player)
        r.detail = "clickSlot slot=$idx button=$button input=$input"
    }

    private fun assertMaidEquipment(mc: Minecraft, c: JsonObject, r: Result) {
        val maid = nearestMaidEntity(mc)
        if (maid == null) {
            r.fail("附近没有女仆实体")
            return
        }
        val slotName = str(c, "slot", "head")!!.uppercase()
        val slot = try {
            EquipmentSlot.valueOf(slotName)
        } catch (_: IllegalArgumentException) {
            r.fail("未知装备槽: $slotName")
            return
        }
        val stack = maid.getItemBySlot(slot)
        if (!matchClientStack(stack, c)) {
            r.fail("女仆 $slot 实际=" + clientItemName(stack) + " x" + stack.count + "，期望=" + c)
        } else {
            r.detail = "女仆 $slot = " + clientItemName(stack) + " x" + stack.count
        }
    }

    private fun matchClientStack(stack: ItemStack, c: JsonObject): Boolean {
        var ok = true
        if (c.has("empty")) {
            ok = ok && (stack.isEmpty == c.get("empty").asBoolean)
        }
        if (c.has("item")) {
            ok = ok && clientItemName(stack) == c.get("item").asString
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

    private fun clientItemName(stack: ItemStack): String =
        if (stack.isEmpty) "minecraft:air" else BuiltInRegistries.ITEM.getKey(stack.item).toString()

    private fun nearestMaidEntity(mc: Minecraft): SmartMaidEntity? {
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        var best: SmartMaidEntity? = null
        var bestDist = Double.MAX_VALUE
        for (e in level.entitiesForRendering()) {
            if (e is SmartMaidEntity) {
                val d = e.distanceToSqr(player)
                if (d < bestDist) {
                    bestDist = d
                    best = e
                }
            }
        }
        return best
    }

    private fun clickAt(mc: Minecraft, rect: IntArray?, idx: Int, what: String): String? {
        if (rect == null) {
            return null
        }
        val sc = mc.gui.screen() ?: return "（无 Screen）"
        val cx = rect[0] + rect[2] / 2.0
        val cy = rect[1] + rect[3] / 2.0
        sc.mouseMoved(cx, cy)
        val handled = sc.mouseClicked(
            MouseButtonEvent(cx, cy, MouseButtonInfo(MaidCompat.primaryMouseButton(), 0)), false
        )
        val detail = String.format(
            "点 %s#%d @(%.0f,%.0f) rect=%s handled=%s → Screen=%s",
            what, idx, cx, cy, rect.contentToString(), handled, screenName(mc)
        )
        if (!handled) {
            throw IllegalStateException("点击未被 Screen 处理：" + detail)
        }
        return detail
    }

    // ------------------------------------------------------------------ 按键采样

    private fun startPoll(c: JsonObject, r: Result) {
        pollTicks = num(c, "ticks", 60)
        pollSeen.clear()
        r.detail = "开始采样按键 " + pollTicks + " tick：请在这段时间内**按住**要测的键（如 Shift、E）"
        results!!.add(GSON.toJsonTree(r))      // poll 先占位，结束时再更新
    }

    private fun samplePoll(mc: Minecraft) {
        pollTicks--
        val shifts = SmartMaidClient.shiftKeyCodes()
        val leftKey = "LShift(" + shifts[0] + ")"
        val rightKey = "RShift(" + shifts[1] + ")"
        pollSeen[leftKey] = (pollSeen[leftKey] ?: false) || MaidCompat.isKeyDown(shifts[0])
        pollSeen[rightKey] = (pollSeen[rightKey] ?: false) || MaidCompat.isKeyDown(shifts[1])
        pollSeen["KeyMapping.isDown"] =
            (pollSeen["KeyMapping.isDown"] ?: false) || SmartMaidClient.OPEN_INVENTORY.isDown
        if (pollTicks > 0) {
            return
        }
        // 更新占位结果
        val last = results!!.get(results!!.size() - 1).asJsonObject
        val sb = StringBuilder("采样结果: ")
        var any = false
        for ((key, value) in pollSeen) {
            sb.append(key).append('=').append(value).append("  ")
            any = any || value
        }
        if (any) {
            last.addProperty("detail", sb.toString())
        } else {
            last.addProperty("status", "FAIL")
            last.addProperty(
                "detail",
                sb.toString() + "→ 一个键都没读到。若你确实按住了，说明键值编码不对"
            )
            MaidErrorSink.report(
                "clienttest/pollKeys",
                "按键采样一个都没命中（键值编码可能仍不对）", null
            )
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun screenName(mc: Minecraft): String {
        val s = mc.gui.screen()
        return if (s == null) "(null)" else s.javaClass.simpleName
    }

    private fun nearestMaidId(mc: Minecraft): Int {
        val level = mc.level ?: return -1
        val player = mc.player ?: return -1
        var best = Double.MAX_VALUE
        var id = -1
        for (e in level.entitiesForRendering()) {
            if (e is SmartMaidEntity) {
                val d = e.distanceToSqr(player)
                if (d < best) {
                    best = d
                    id = e.id
                }
            }
        }
        return id
    }

    private fun env(mc: Minecraft?): JsonObject {
        val o = JsonObject()
        if (mc == null) {
            return o
        }
        o.addProperty("inWorld", mc.level != null)
        o.addProperty("screen", screenName(mc))
        o.addProperty("nearestMaidId", nearestMaidId(mc))
        val km = SmartMaidClient.OPEN_INVENTORY
        o.addProperty("openInventoryKey", km.defaultKey.name)
        o.addProperty("openInventoryKeyValue", km.defaultKey.value)
        o.addProperty("openInventoryKeyType", km.defaultKey.type.toString())
        val sh = JsonArray()
        for (k in SmartMaidClient.shiftKeyCodes()) {
            sh.add(k)
        }
        o.add("shiftKeyCodes", sh)
        return o
    }

    private fun addResult(r: Result) {
        results!!.add(GSON.toJsonTree(r))
        if ("PASS" != r.status) {
            MaidErrorSink.report("clienttest/" + r.id, r.op + " → " + r.status + "：" + r.detail, r.error)
        }
    }

    private fun str(o: JsonObject, k: String, def: String?): String? {
        return if (o.has(k) && !o.get(k).isJsonNull) o.get(k).asString else def
    }

    private fun num(o: JsonObject, k: String, def: Int): Int {
        return if (o.has(k) && !o.get(k).isJsonNull) o.get(k).asInt else def
    }

    /** 非阻塞等待：每客户端 tick 检查一次，命中或超时后写结果。 */
    private class WaitCheck(
        val kind: String,
        val expect: String?,
        val min: Int,
        var remaining: Int,
        val result: Result,
    )

    /** 单条用例结果（序列化成报告里的一个元素）。 */
    private class Result(
        val id: String,
        val op: String,
        var status: String,
    ) {
        var detail: String? = null

        @Transient
        var error: Throwable? = null

        fun fail(why: String): Result {
            this.status = "FAIL"
            this.detail = why
            return this
        }

        fun error(t: Throwable): Result {
            this.status = "ERROR"
            this.error = t
            this.detail = t.toString()
            return this
        }
    }
}
