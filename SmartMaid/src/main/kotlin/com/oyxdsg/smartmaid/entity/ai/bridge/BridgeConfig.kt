package com.oyxdsg.smartmaid.entity.ai.bridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.oyxdsg.smartmaid.SmartMaid
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 桌宠桥接配置：{@code config/smartmaid/bridge.json}（首次运行自动生成默认配置）。
 *
 * <pre>{@code
 * {
 *   "enabled": false,                     // 总开关（遥测下行）；默认关 —— 整合包/服务器通常没有桌宠
 *   "deskpetDir": "",                     // 遥测目录，留空 = <游戏目录>/deskpet/maid
 *   "windowTicks": 400,                   // 遥测窗口聚合周期（20s）
 *   "ws": {
 *     "enabled": false,                   // 上行通道总开关；默认关
 *     "url": "ws://127.0.0.1:21420",      // 桌宠侧 WebSocket Server
 *     "token": "",                        // 握手 token，与桌宠配置一致（留空 = 不校验）
 *     "heartbeatSec": 5,
 *     "perceptionIntervalMs": 750         // 感知快照上报间隔
 *   }
 * }
 * }</pre>
 *
 * <p><b>默认关闭的理由</b>（2026-09-29）：桥接只对"本机跑着桌宠"的用户有意义。
 * 默认开启会导致：没有桌宠的整合包/服务器每次启动都白建 {@code deskpet/} 目录写文件、
 * 并持续空连 {@code ws://127.0.0.1:21420}（指数退避）。已有配置文件不会被覆盖，
 * 所以老用户升级后行为不变；新装用户需要联动时手动改 true。</p>
 *
 * <p>加载时机：首次访问任意 getter 时懒加载一次（服务端线程调用）。</p>
 */
object BridgeConfig {
    private val GSON = Gson()
    private const val FILE_NAME = "smartmaid/bridge.json"

    private const val DEFAULT_WINDOW_TICKS = 400

    private var loaded = false
    private var enabled = false
    private var deskpetDir: Path? = null
    private var windowTicks = DEFAULT_WINDOW_TICKS
    private var wsEnabled = false
    private var wsUrl = "ws://127.0.0.1:21420"
    private var wsToken = ""
    private var heartbeatSec = 5
    private var perceptionIntervalMs = 750

    /** 懒加载配置（幂等） */
    @JvmStatic
    @Synchronized
    fun load() {
        if (loaded) {
            return
        }
        loaded = true
        val file = FabricLoader.getInstance().configDir.resolve(FILE_NAME)
        val cfg: JsonObject
        if (Files.isRegularFile(file)) {
            var parsed: JsonObject? = null
            try {
                parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .asJsonObject
            } catch (e: IOException) {
                SmartMaid.LOGGER.warn("bridge.json 解析失败，使用默认配置: {}", e.toString())
            } catch (e: JsonSyntaxException) {
                SmartMaid.LOGGER.warn("bridge.json 解析失败，使用默认配置: {}", e.toString())
            } catch (e: IllegalStateException) {
                SmartMaid.LOGGER.warn("bridge.json 解析失败，使用默认配置: {}", e.toString())
            }
            cfg = parsed ?: defaults()
        } else {
            val defaults = defaults()
            writeDefault(file, defaults)
            cfg = defaults
        }
        apply(cfg)
    }

    private fun defaults(): JsonObject {
        val cfg = JsonObject()
        // 默认关闭：无桌宠的整合包/服务器不该产生多余 IO 与空连接
        cfg.addProperty("enabled", false)
        cfg.addProperty("deskpetDir", "")
        cfg.addProperty("windowTicks", DEFAULT_WINDOW_TICKS)
        val ws = JsonObject()
        ws.addProperty("enabled", false)
        ws.addProperty("url", "ws://127.0.0.1:21420")
        ws.addProperty("token", "")
        ws.addProperty("heartbeatSec", 5)
        ws.addProperty("perceptionIntervalMs", 750)
        cfg.add("ws", ws)
        return cfg
    }

    private fun writeDefault(file: Path, cfg: JsonObject) {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8)
            SmartMaid.LOGGER.info("已生成默认桥接配置: {}", file)
        } catch (e: IOException) {
            SmartMaid.LOGGER.warn("bridge.json 写入失败: {}", e.toString())
        }
    }

    private fun apply(cfg: JsonObject) {
        enabled = !cfg.has("enabled") || cfg.get("enabled").asBoolean
        if (cfg.has("windowTicks") && cfg.get("windowTicks").isJsonPrimitive) {
            windowTicks = Math.max(20, cfg.get("windowTicks").asInt)
        }
        // 遥测目录：留空则用 <游戏目录>/deskpet/maid（桌宠按 <mc>/.minecraft/deskpet 推导，天然对齐）
        val custom: String? = if (cfg.has("deskpetDir")) cfg.get("deskpetDir").asString else ""
        deskpetDir = if (!custom.isNullOrBlank()) {
            Path.of(custom)
        } else {
            FabricLoader.getInstance().gameDir.resolve("deskpet").resolve("maid")
        }

        if (cfg.has("ws") && cfg.get("ws").isJsonObject) {
            val ws = cfg.getAsJsonObject("ws")
            wsEnabled = !ws.has("enabled") || ws.get("enabled").asBoolean
            if (ws.has("url")) {
                wsUrl = ws.get("url").asString
            }
            if (ws.has("token")) {
                wsToken = ws.get("token").asString
            }
            if (ws.has("heartbeatSec") && ws.get("heartbeatSec").isJsonPrimitive) {
                heartbeatSec = Math.max(1, ws.get("heartbeatSec").asInt)
            }
            if (ws.has("perceptionIntervalMs") && ws.get("perceptionIntervalMs").isJsonPrimitive) {
                perceptionIntervalMs = Math.max(100, ws.get("perceptionIntervalMs").asInt)
            }
        }
    }

    @JvmStatic
    fun enabled(): Boolean {
        load()
        return enabled
    }

    @JvmStatic
    fun deskpetDir(): Path {
        load()
        return deskpetDir!!
    }

    @JvmStatic
    fun windowTicks(): Int {
        load()
        return windowTicks
    }

    @JvmStatic
    fun wsEnabled(): Boolean {
        load()
        return wsEnabled
    }

    @JvmStatic
    fun wsUrl(): String {
        load()
        return wsUrl
    }

    @JvmStatic
    fun wsToken(): String {
        load()
        return wsToken
    }

    @JvmStatic
    fun heartbeatSec(): Int {
        load()
        return heartbeatSec
    }

    @JvmStatic
    fun perceptionIntervalMs(): Int {
        load()
        return perceptionIntervalMs
    }
}
