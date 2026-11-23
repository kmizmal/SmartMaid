package com.oyxdsg.smartmaid.entity.ai.bridge

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask
import java.io.IOException
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.min

/**
 * 女仆遥测下行（M5-a）：把女仆的感知事件按桌面宠物的既有格式落盘，
 * 让桌宠「看到」女仆，而不需要改造桌宠的数据通路。
 *
 * <p>写入位置：{@code <游戏目录>/deskpet/maid/YYYYMMDD-HHMM.jsonl}，每行一个窗口 JSON。
 * 目录刻意与 {@code deskpet/} 顶层隔离——顶层是 deskpet-mod 的写入区，混写会产生同分钟文件竞争。</p>
 *
 * <p>窗口 schema 对齐桌宠 {@code game/mod_data.py} 的既有约定：</p>
 * <pre>{@code
 * {"source":"smartmaid","seq":3,"tick":123456,"importance":"NORMAL",
 *  "highlights":[{"type":"damage","player":"女仆","target":"zombie","detail":"剩余生命 18.0"}],
 *  "maid":{"name":"女仆","owner":"Steve","dim":"...","pos":[x,y,z],"health":18.0,"task":"mine",...}}
 * }</pre>
 *
 * <p>{@code highlights[].type} 复用桌宠已认识的取值（damage / summary），
 * 因此桌宠侧 {@code highlights_to_text} 无需改动即可正确渲染中文描述；
 * 多出的 {@code maid} 字段桌宠会忽略，属于额外状态。</p>
 *
 * <p>配置：{@code config/smartmaid/bridge.json}，见 {@link BridgeConfig}。</p>
 *
 * <p>驱动：{@link SmartMaidEntity#aiStep()} 服务端分支每 tick 调用
 * {@link #onMaidTick(SmartMaidEntity)}。</p>
 */
object MaidTelemetryWriter {
    /** 旧文件保留时长（毫秒），与 WindowSink 一致 */
    private const val RETENTION_MS = 2 * 60 * 1000L

    /**
     * 首窗口提前落盘的 tick 数（5s）。
     * 默认窗口 20s，但女仆刚召唤时如果玩家很快退出/暂停，可能一个窗口都凑不满；
     * 首窗口缩短保证"召唤后 5 秒内必定能看到一份遥测"。
     */
    private const val FIRST_WINDOW_TICKS = 100
    private val FILE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val GSON = Gson()

    /** 未指定时的兜底显示名 */
    private const val FALLBACK_NAME = "女仆"

    private val BUFFERS = WeakHashMap<SmartMaidEntity, WindowBuffer>()

    private var currentMinute = ""
    private var writer: Writer? = null

    // ---------- 对外入口 ----------

    /** 每 tick 驱动（服务端）：累积事件，到窗口边界落盘一次 */
    @JvmStatic
    fun onMaidTick(maid: SmartMaidEntity) {
        if (maid.level().isClientSide) {
            return
        }
        if (!BridgeConfig.enabled()) {
            return
        }
        val buf: WindowBuffer = BUFFERS[maid] ?: WindowBuffer().also { newBuf ->
            BUFFERS[maid] = newBuf
            // 事件订阅挂到感知模块：事件产生即入缓冲，不等快照
            maid.perceptionModule.addEventSink { ev -> onEvent(newBuf, ev) }
        }
        buf.ticksInWindow++
        // 首窗口提前落盘（5s），之后按配置周期（默认 20s）
        val limit = if (buf.seq == 0) {
            min(BridgeConfig.windowTicks(), FIRST_WINDOW_TICKS)
        } else {
            BridgeConfig.windowTicks()
        }
        if (buf.ticksInWindow >= limit) {
            flush(maid, buf)
        }
    }

    /** 女仆死亡/移除时清理（WeakHashMap 也会自动回收，此处为即时释放） */
    @JvmStatic
    fun forget(maid: SmartMaidEntity) {
        BUFFERS.remove(maid)
        maid.perceptionModule.clearEventSinks()
    }

    // ---------- 事件 → highlight ----------

    private fun onEvent(buf: WindowBuffer, ev: JsonObject) {
        val type = str(ev, "type", "")
        val data = if (ev.has("data") && ev.get("data").isJsonObject) {
            ev.getAsJsonObject("data")
        } else {
            JsonObject()
        }
        when (type) {
            "hurt" -> {
                // 复用桌宠已认识的 damage 类型 → 渲染为「[受伤] 女仆 被 zombie 伤害」
                val h = highlight("damage")
                h.addProperty("player", FALLBACK_NAME)
                val target = str(data, "attacker", "unknown")
                h.addProperty("target", target)
                h.addProperty("detail", "剩余生命 " + num(data, "health"))
                buf.highlights.add(h)
                // 攻击者未知（环境伤害 / 解析失败）→ 降级 NORMAL，避免误报"被谁打了"
                if (target == "unknown") {
                    buf.normal = true
                } else {
                    buf.critical = true
                }
            }
            "enemy_spotted" -> {
                buf.highlights.add(summary("女仆发现 " + str(data, "type", "?")
                        + "（" + num(data, "dist") + " 格外）"))
                buf.critical = true
            }
            "environment_danger" -> {
                val danger = when (str(data, "danger", "")) {
                    "water" -> "水域"
                    "lava" -> "岩浆"
                    "fire" -> "火焰"
                    else -> "危险"
                }
                val enter = data.has("enter") && data.get("enter").asBoolean
                buf.highlights.add(summary("女仆" + (if (enter) "进入" else "离开") + danger))
                buf.critical = true
            }
            "task_started" -> {
                buf.highlights.add(summary("女仆开始执行 " + str(data, "task", "?")))
                buf.normal = true
            }
            "task_done" -> {
                buf.highlights.add(summary("女仆完成了 " + str(data, "task", "?")))
                buf.normal = true
            }
            else -> buf.highlights.add(summary(type))
        }
    }

    // ---------- 窗口落盘 ----------

    private fun flush(maid: SmartMaidEntity, buf: WindowBuffer) {
        buf.ticksInWindow = 0
        val highlights = ArrayList(buf.highlights)
        val importance = if (buf.critical) "CRITICAL" else (if (buf.normal) "NORMAL" else "LOW")
        buf.highlights.clear()
        buf.critical = false
        buf.normal = false
        buf.seq++
        try {
            val win = JsonObject()
            win.addProperty("source", SmartMaid.MOD_ID)
            win.addProperty("seq", buf.seq)
            win.addProperty("tick", maid.level().levelData.gameTime)
            win.addProperty("time", LocalDateTime.now().toString())
            win.addProperty("importance", importance)
            val arr = JsonArray()
            highlights.forEach { arr.add(it) }
            win.add("highlights", arr)
            win.add("maid", maidBlock(maid))
            writeLine(win)
            MaidDebug.log("遥测窗口写入: " + importance + " 事件=" + highlights.size
                    + " seq=" + buf.seq + " → " + BridgeConfig.deskpetDir())
        } catch (e: Exception) {
            SmartMaid.LOGGER.warn("女仆遥测写入失败: {}", e.toString())
        }
    }

    /** 女仆当前状态块（窗口附带），桌宠可忽略；也是联调时最直观的排查对象 */
    private fun maidBlock(maid: SmartMaidEntity): JsonObject {
        val m = JsonObject()
        m.addProperty("name", maid.baseName())
        m.addProperty("owner", maid.owner?.name?.string)
        m.addProperty("dim", maid.level().dimension().identifier().toString())
        val pos = JsonArray()
        pos.add(round(maid.x))
        pos.add(round(maid.y))
        pos.add(round(maid.z))
        m.add("pos", pos)
        m.addProperty("health", round(maid.health.toDouble()))
        m.addProperty("max_health", round(maid.maxHealth.toDouble()))
        val task: MaidAITask? = maid.maidTaskManager.currentTask()
        m.addProperty("task", task?.taskId())
        m.addProperty("ai_busy", maid.isAiBusy)
        m.addProperty("sitting", maid.isOrderedToSit)
        m.addProperty("on_ground", maid.onGround())
        m.addProperty("mainhand", MaidDebug.itemName(maid.mainHandItem))
        return m
    }

    @Throws(IOException::class)
    private fun writeLine(win: JsonObject) {
        val minute = LocalDateTime.now().format(FILE_FMT)
        val dir = BridgeConfig.deskpetDir()
        if (minute != currentMinute) {
            closeWriter()
            currentMinute = minute
            Files.createDirectories(dir)
            writer = Files.newBufferedWriter(
                dir.resolve(minute + ".jsonl"),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
            cleanup(dir)
        }
        writer!!.write(GSON.toJson(win))
        writer!!.write("\n")
        writer!!.flush()
    }

    private fun closeWriter() {
        if (writer != null) {
            try {
                writer!!.close()
            } catch (_: IOException) {
                // 关闭失败不影响后续写入（下次轮转会新建 writer）
            }
            writer = null
        }
    }

    /** 删除 2 分钟前的 .jsonl（桌宠只关心新鲜数据，避免无限堆积） */
    private fun cleanup(dir: Path) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        try {
            val paths = Files.list(dir)
            paths.use { paths ->
                paths.filter { p -> p.toString().endsWith(".jsonl") }
                    .filter { p ->
                        try {
                            Files.getLastModifiedTime(p).toMillis() < cutoff
                        } catch (_: IOException) {
                            false
                        }
                    }
                    .forEach { p ->
                        try {
                            Files.deleteIfExists(p)
                        } catch (_: IOException) {
                            // 文件被桌宠占用时跳过，下个窗口再清
                        }
                    }
            }
        } catch (_: IOException) {
            // 目录尚不存在等情况：忽略
        }
    }

    // ---------- 小工具 ----------

    private fun highlight(type: String): JsonObject {
        val h = JsonObject()
        h.addProperty("type", type)
        return h
    }

    private fun summary(detail: String): JsonObject {
        val h = highlight("summary")
        h.addProperty("detail", detail)
        return h
    }

    private fun str(o: JsonObject, key: String, def: String): String {
        return if (o.has(key) && !o.get(key).isJsonNull) o.get(key).asString else def
    }

    private fun num(o: JsonObject, key: String): String {
        return if (o.has(key) && !o.get(key).isJsonNull) o.get(key).asString else "?"
    }

    private fun round(v: Double): Double {
        return Math.round(v * 10.0) / 10.0
    }

    /** 单个女仆的窗口缓冲 */
    private class WindowBuffer {
        val highlights = ArrayList<JsonObject>()
        var ticksInWindow = 0
        var seq = 0
        var critical = false
        var normal = false
    }
}
