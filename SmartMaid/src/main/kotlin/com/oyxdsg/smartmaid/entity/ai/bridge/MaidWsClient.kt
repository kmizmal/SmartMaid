package com.oyxdsg.smartmaid.entity.ai.bridge

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionDiff
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletionStage
import kotlin.math.max
import kotlin.math.min

/**
 * 桌宠联动上行/下行通道（M5-b）：模组作为 WebSocket Client 连接桌宠侧 Server
 * `ws://127.0.0.1:21420`，实现「女仆上报感知、桌宠下发指令」的闭环。
 *
 * <p>用 JDK 内置 `java.net.http.WebSocket`，不引入任何新依赖。</p>
 *
 * <h3>协议</h3>
 * 模组 → 桌宠：
 * <pre>{@code
 * {"type":"hello","mod":"smartmaid","version":"...","token":"...","maids":[{"id","name","owner"}]}
 * {"type":"perception","maid":"<uuid>","name":"女仆","owner":"Steve",
 *  "seq":3,"tick":123,"full":false,"changed":{...},"removed":["self.status.target"]}
 * {"type":"perception","maid":"<uuid>","seq":1,"tick":100,"full":true,"snapshot":{...}}
 * {"type":"event","maid":"<uuid>","event":{"t":100,"type":"hurt","data":{...}}}
 * {"type":"command_result","maid":"<uuid>","reply":{"id","ok","state","step","result"}}
 * {"type":"pong","t":123}
 * }</pre>
 *
 * 桌宠 → 模组：
 * <pre>{@code
 * {"type":"hello_ack","ok":true}
 * {"type":"command","maid":"","id":"c1","cmd":"mine","params":{"pos":[100,-60,-50],"range":4}}
 * {"type":"command","maid":"","json":"{...完整指令 JSON，直接透传...}"}
 * {"type":"speak","maid":"","text":"好的主人","ticks":80}
 * {"type":"animation","maid":"","name":"waving"}
 * {"type":"ping","t":123}
 * }</pre>
 *
 * <h3>线程模型（关键）</h3>
 * WebSocket 回调运行在 [HttpClient] 线程池，**绝不能直接触碰世界状态**。
 * 收到消息后一律先落地到 [OUTBOX] 或经 `server.execute(...)` 切回服务端线程，
 * 再调用 [MaidAIBridge] / 实体 API。发送也统一由服务端 tick 从 OUTBOX 抽取，
 * 保证单线程串行写，避免多线程同时 `sendText` 造成分帧。
 *
 * <p>配置见 [BridgeConfig]（`ws.enabled/url/token/heartbeatSec/perceptionIntervalMs`）。</p>
 */
object MaidWsClient {

    private val GSON = Gson()

    private const val INITIAL_BACKOFF_MS = 1000L
    private const val MAX_BACKOFF_MS = 30000L
    /** 单次 tick 最多发送的消息数，避免异常情况下刷屏 */
    private const val MAX_SEND_PER_TICK = 64

    private const val HANDSHAKE_TIMEOUT_MS = 10_000L
    private val HTTP: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()
    private val SESSION = MaidWsSession<SmartMaidEntity>({ it.uuid }) { maid ->
        val srv = server
        srv != null && maid.level().server === srv && maid.isAlive && !maid.isRemoved
    }

    /** 每女仆的通道状态（增量 diff 基线 + 上报节流） */
    private val CHANNELS: MutableMap<SmartMaidEntity, Channel> = WeakHashMap()

    @Volatile
    private var socket: WebSocket? = null

    private val open: Boolean get() = SESSION.open
    private val handshaken: Boolean get() = SESSION.handshaken
    // Connection attempts and world access are confined to the owning server thread.
    private var pending: Listener? = null
    private var wsConfig = BridgeConfig.WsConfig()
    private var nextConfigPoll = 0L
    private var handshakeDeadline = 0L
    private var sending: CompletionStage<*>? = null
    private var nextAttemptAt = 0L
    private var backoffMs = INITIAL_BACKOFF_MS
    private var lastPingAt = 0L
    private var pongLogged = false

    @Volatile
    private var server: MinecraftServer? = null

    /** 动作名 → id（懒加载，构建失败时只支持数字 id） */
    private var animIds: MutableMap<String, Int>? = null

    // ---------- 生命周期 ----------

    @JvmStatic
    fun onServerStarted(srv: MinecraftServer) {
        // 专用服务器上没有"本机桌宠"这回事：直接跳过，避免无意义的重连与目录写入。
        // 判定必须用 isDedicatedServer() —— 单人游戏的集成服务器也跑在客户端进程里，
        // 用 FabricLoader.getEnvironmentType() 会把单人一起关掉。
        if (srv.isDedicatedServer) {
            SmartMaid.LOGGER.info("专用服务器：跳过桌宠联动（桥接仅对本机桌宠有意义）")
            return
        }
        server = srv
        nextAttemptAt = 0
        backoffMs = INITIAL_BACKOFF_MS
        BridgeConfig.load()
        nextConfigPoll = 0
        refreshConfig()
    }

    @JvmStatic
    fun onServerStopping(srv: MinecraftServer) {
        if (server !== srv) return
        handleDisconnect("server stopping")
        server = null
        wsConfig = BridgeConfig.WsConfig()
        CHANNELS.clear()
        SESSION.endWorld()
        SmartMaid.LOGGER.info("女仆 WebSocket 已停止")
    }

    // ---------- 每 tick 驱动（服务端线程） ----------

    @JvmStatic
    fun onServerTick(srv: MinecraftServer) {
        if (server !== srv) return
        if (!refreshConfig()) return
        val now = System.currentTimeMillis()
        if (!open) {
            tryConnect(srv, now)
            return
        }
        if (!handshaken) {
            if (now >= handshakeDeadline) handleDisconnect("hello_ack timeout")
            return
        }
        // 心跳
        if (now - lastPingAt >= wsConfig.heartbeatSec * 1000L) {
            lastPingAt = now
            val ping = JsonObject()
            ping.addProperty("type", "ping")
            ping.addProperty("t", now)
            enqueue(ping)
        }
        // 感知上报
        for ((maid, value) in ArrayList(CHANNELS.entries)) {
            if (!maid.isAlive || maid.isRemoved) {
                CHANNELS.remove(maid)
                continue
            }
            sendPerception(maid, value, now)
        }
        drainOutbox()
    }

    /** 女仆侧注册（由 [SmartMaidEntity.aiStep] 调用），并把事件订阅挂到感知模块 */
    @JvmStatic
    fun onMaidTick(maid: SmartMaidEntity) {
        val srv = server ?: return
        if (maid.level().isClientSide || maid.level().server !== srv || !maid.isAlive || maid.isRemoved) return
        SESSION.observe(maid)
        if (!handshaken) return
        val first = !CHANNELS.containsKey(maid)
        var channel = CHANNELS[maid]
        if (channel == null) {
            channel = Channel()
            CHANNELS[maid] = channel
            maid.perceptionModule.addEventSink { ev -> onMaidEvent(maid, ev) }
        }
        if (first) {
            // 女仆生成：立即推送一次**全量**感知（含完整背包），桌宠一出现就有状态，
            // 不必等定时首帧（此前感知就绪前反查背包会误报「背包里没有」）。
            pushFullSnapshot(maid, channel)
        }
    }

    /**
     * 女仆生成时立即推送一次全量感知快照：重置 diff（强制 full）+ 强制全量采样 +
     * 立即发送。这样桌宠在女仆出现瞬间就拿到完整状态（背包/装备/主人/周边）。
     */
    private fun pushFullSnapshot(maid: SmartMaidEntity, channel: Channel) {
        try {
            channel.diff.reset()
            channel.lastPerceptionAt = 0
            val perception = maid.perceptionModule.snapshotNow() ?: return
            sendPerception(maid, channel, perception)
            MaidDebug.log("女仆生成全量感知已推送: " + maid.baseName())
        } catch (t: Throwable) {
            MaidDebug.log("女仆生成全量推送失败: " + t)
        }
    }

    // ---------- 对话 / 语音 / 存在通知（供网络包与服务端逻辑调用） ----------

    /** 玩家在聊天栏对女仆说的话 → 上行给桌宠 AI（服务端线程调用）。 */
    @JvmStatic
    fun sendChat(maid: SmartMaidEntity?, text: String?): Boolean {
        if (maid == null || text.isNullOrBlank()) return false
        return SESSION.queueChat(::refreshConfig, { socket != null }) {
            val out = JsonObject()
            out.addProperty("type", "chat")
            out.addProperty("maid", maid.getUUID().toString())
            out.addProperty("text", text)
            GSON.toJson(out)
        }
    }

    /**
     * 女仆存在通知（上线/离线）：桌宠侧据此决定显示或隐退（隐退开关在桌宠设置里）。
     */
    @JvmStatic
    fun notifyPresence(maid: SmartMaidEntity?, online: Boolean) {
        val srv = server ?: return
        if (maid != null) {
            if (maid.level().server !== srv) return
            if (online) SESSION.observe(maid) else SESSION.forget(maid)
        }
        // The hello roster covers the period before the socket opens. Preserve transitions
        // after hello, but do not send them until the peer acknowledges the handshake.
        if (!open) return
        val out = JsonObject()
        out.addProperty("type", "maid_presence")
        if (maid != null) {
            out.addProperty("maid", maid.getUUID().toString())
            out.addProperty("name", maid.baseName())
        }
        out.addProperty("online", online)
        enqueue(out)
        MaidDebug.log("女仆 presence online=" + online +
            if (maid == null) "" else " " + maid.baseName())
    }

    // ---------- 上行 ----------

    private fun onMaidEvent(maid: SmartMaidEntity, ev: JsonObject) {
        if (!handshaken) {
            return
        }
        val out = JsonObject()
        out.addProperty("type", "event")
        out.addProperty("maid", maid.getUUID().toString())
        out.add("event", ev.deepCopy())
        enqueue(out)
    }

    private fun sendPerception(maid: SmartMaidEntity, channel: Channel, now: Long) {
        val interval = wsConfig.perceptionIntervalMs
        if (now - channel.lastPerceptionAt < interval) {
            return
        }
        val perception = maid.perceptionModule.current() ?: return
        channel.lastPerceptionAt = now
        sendPerception(maid, channel, perception)
    }

    /** 组装并发出一条 perception 消息（增量 diff 字段平铺到信封上）。 */
    private fun sendPerception(maid: SmartMaidEntity, channel: Channel, perception: MaidPerception) {
        val message = JsonObject()
        message.addProperty("type", "perception")
        message.addProperty("maid", maid.getUUID().toString())
        message.addProperty("name", maid.baseName())
        message.addProperty("owner", maid.owner?.name?.string)
        // 增量 diff 的字段（seq/tick/full/snapshot 或 changed/removed）平铺到信封上
        channel.diff.next(perception).entrySet()
            .forEach { e -> message.add(e.key, e.value) }
        if (!enqueue(message)) {
            channel.diff.reset()
            return
        }
        if (channel.diff.seq() == 1L) {
            // 只打一次：证明感知上报链路真的跑起来了（后续每 750ms 一条，不宜逐条打日志）
            MaidDebug.log("开始上报感知: " + maid.baseName() +
                "（间隔 " + wsConfig.perceptionIntervalMs + "ms，首帧全量）")
        }
    }

    private fun enqueue(message: JsonObject): Boolean =
        SESSION.enqueue(GSON.toJson(message))

    private fun drainOutbox() {
        val ws = socket ?: return
        val srv = server ?: return
        repeat(MAX_SEND_PER_TICK) {
            if (sending?.toCompletableFuture()?.isDone == false) return
            val text = SESSION.pollOutgoing() ?: return
            if (!send(srv, ws, text)) return
        }
    }

    private fun send(srv: MinecraftServer, ws: WebSocket, text: String): Boolean {
        return try {
            sending = ws.sendText(text, true).whenComplete { _, error ->
                if (error != null) srv.execute {
                    if (server === srv && socket === ws) handleDisconnect("send failed")
                }
            }
            true
        } catch (_: Exception) {
            if (socket === ws) handleDisconnect("send failed")
            false
        }
    }

    // ---------- 连接管理 ----------

    private fun refreshConfig(): Boolean {
        val now = System.nanoTime()
        if (nextConfigPoll == 0L || now - nextConfigPoll >= 0) {
            nextConfigPoll = now + 1_000_000_000L
            val updated = BridgeConfig.readWsConfig()
            if (updated.transport != wsConfig.transport) {
                if (open || pending != null) handleDisconnect("configuration changed")
                nextAttemptAt = 0
                backoffMs = INITIAL_BACKOFF_MS
                if (updated.transport.enabled) {
                    val error = updated.transport.rejection()
                    if (error == null) SmartMaid.LOGGER.info("女仆 WebSocket 已启用")
                    else SmartMaid.LOGGER.error("桌宠 WebSocket 配置被拒绝: {}", error)
                }
            }
            wsConfig = updated
        }
        return wsConfig.transport.enabled && wsConfig.transport.rejection() == null
    }

    private fun tryConnect(srv: MinecraftServer, now: Long) {
        if (pending != null || now < nextAttemptAt) return
        val listener = Listener(srv, wsConfig.transport)
        pending = listener
        try {
            listener.connection = HTTP.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create(listener.transport.url), listener)
                .whenComplete { _, error ->
                    if (error != null) srv.execute {
                        if (server === srv && pending === listener) handleDisconnect("connection failed")
                    }
                }
        } catch (_: Exception) {
            handleDisconnect("connection failed")
        }
    }

    private fun onOpen(srv: MinecraftServer, listener: Listener, ws: WebSocket) {
        if (server !== srv || pending !== listener || !refreshConfig() || pending !== listener) {
            ws.abort()
            if (server === srv && pending === listener) handleDisconnect("config disabled or rejected")
            return
        }
        pending = null
        socket = ws
        SESSION.markOpened()
        handshakeDeadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        lastPingAt = 0
        pongLogged = false
        seedRoster(srv)
        val hello = JsonObject()
        hello.addProperty("type", "hello")
        hello.addProperty("mod", SmartMaid.MOD_ID)
        hello.addProperty("token", listener.transport.token)
        val maids = JsonArray()
        for (maid in aliveMaids()) {
            val m = JsonObject()
            m.addProperty("id", maid.getUUID().toString())
            m.addProperty("name", maid.baseName())
            m.addProperty("owner", maid.owner?.name?.string)
            maids.add(m)
        }
        hello.add("maids", maids)
        if (send(srv, ws, GSON.toJson(hello))) ws.request(1)
    }

    private fun handleDisconnect(reason: String) {
        val ws = socket
        socket = null
        SESSION.disconnect()
        sending = null
        val attempt = pending
        pending = null
        attempt?.cancel()
        nextAttemptAt = System.currentTimeMillis() + backoffMs
        backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
        CHANNELS.values.forEach { it.diff.reset(); it.lastPerceptionAt = 0 }
        if (ws != null) {
            MaidDebug.log("桌宠 WebSocket 断开（$reason），稍后重连")
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, reason).whenComplete { _, _ -> ws.abort() }
            } catch (_: Exception) {
                ws.abort()
            }
        }
    }

    // ---------- 下行分发（服务端线程） ----------

    private fun dispatch(srv: MinecraftServer, ws: WebSocket, text: String) {
        if (server !== srv || socket !== ws || !open) return
        if (!refreshConfig() || socket !== ws) return
        val msg: JsonObject
        try {
            msg = JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            MaidDebug.log("桌宠下发了非法 JSON: " + e)
            return
        }
        val type = msg.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return
        if (!handshaken && !MaidWsPolicy.allowsBeforeHandshake(type)) return
        when (type) {
            "hello_ack" -> {
                if (!MaidWsPolicy.isHandshakeAccepted(msg)) {
                    handleDisconnect("hello_ack rejected")
                    return
                }
                if (!SESSION.acknowledge()) return
                backoffMs = INITIAL_BACKOFF_MS
                // No pre-auth snapshots are generated; preserve presence changes since hello.
                CHANNELS.values.forEach { it.diff.reset(); it.lastPerceptionAt = 0 }
                MaidDebug.log("握手成功")
                // 物品索引（当前语言的物品名 → id）：桌宠侧据此做中文/拼音模糊匹配，
                // 专用服务端拿不到语言资源时为 null，跳过即可。
                val index = ItemIndexPayload.build()
                if (index != null) {
                    enqueue(index)
                    MaidDebug.log("已下发物品索引 " + index.get("count").asInt + " 条")
                }
            }
            "ping" -> {
                val pong = JsonObject()
                pong.addProperty("type", "pong")
                pong.addProperty("t", System.currentTimeMillis())
                enqueue(pong)
            }
            "pong" -> {
                // 心跳应答：首次记录一次往返延迟，之后静默（避免每 5s 刷日志）
                val sentAt = if (msg.has("t") && msg.get("t").isJsonPrimitive &&
                    msg.get("t").getAsJsonPrimitive().isNumber
                ) msg.get("t").asLong else 0L
                if (sentAt > 0 && !pongLogged) {
                    pongLogged = true
                    MaidDebug.log("心跳往返 " + (System.currentTimeMillis() - sentAt) + "ms")
                }
            }
            "command" -> handleCommand(msg)
            "speak" -> handleSpeak(msg)
            "chat_reply" -> handleChatReply(msg)
            "animation" -> handleAnimation(msg)
            else -> MaidDebug.log("桌宠下发了未知消息类型: " + type)
        }
    }

    private fun handleCommand(msg: JsonObject) {
        if (!handshaken) return
        val maid = resolveMaid(str(msg, "maid", ""))
        if (maid == null) {
            SmartMaid.LOGGER.warn("桌宠指令找不到目标女仆: {}", msg)
            reply(null, MaidCommandResult.fail(str(msg, "id", "ws"), "未找到目标女仆"))
            return
        }
        val result = MaidAIBridge.execute(maid, toCommandJson(msg))
        MaidDebug.log("桌宠指令 → " + result.toJson())
        reply(maid, result)
    }

    private fun reply(maid: SmartMaidEntity?, result: MaidCommandResult) {
        val out = JsonObject()
        out.addProperty("type", "command_result")
        if (maid != null) {
            out.addProperty("maid", maid.getUUID().toString())
        }
        out.add("reply", JsonParser.parseString(result.toJson()))
        enqueue(out)
    }

    /** 把桌宠消息转成 [MaidAIBridge] 认的标准指令 JSON（支持 json 字段直接透传） */
    private fun toCommandJson(msg: JsonObject): String {
        if (msg.has("json") && msg.get("json").isJsonPrimitive) {
            return msg.get("json").asString
        }
        val req = JsonObject()
        req.addProperty("id", str(msg, "id", "ws-" + System.currentTimeMillis()))
        req.addProperty("cmd", str(msg, "cmd", ""))
        req.add("params", if (msg.has("params") && msg.get("params").isJsonObject)
            msg.getAsJsonObject("params") else JsonObject())
        if (msg.has("cancel_previous") && msg.get("cancel_previous").isJsonPrimitive) {
            req.addProperty("cancel_previous", msg.get("cancel_previous").asBoolean)
        }
        if (msg.has("persist") && msg.get("persist").isJsonPrimitive) {
            req.addProperty("persist", msg.get("persist").asBoolean)
        }
        // N1/Q4/Q6：转发队列相关字段（桌宠语音线带 priority:"owner" → 主人 P2；展开多项带 group）
        if (msg.has("queue") && msg.get("queue").isJsonPrimitive) {
            req.addProperty("queue", msg.get("queue").asBoolean)
        }
        if (msg.has("priority") && msg.get("priority").isJsonPrimitive) {
            req.addProperty("priority", msg.get("priority").asString)
        }
        if (msg.has("long_term") && msg.get("long_term").isJsonPrimitive) {
            req.addProperty("long_term", msg.get("long_term").asBoolean)
        }
        if (msg.has("group") && !msg.get("group").isJsonNull) {
            req.addProperty("group", msg.get("group").asString)
        }
        if (msg.has("group_label") && !msg.get("group_label").isJsonNull) {
            req.addProperty("group_label", msg.get("group_label").asString)
        }
        return req.toString()
    }

    private fun handleSpeak(msg: JsonObject) {
        val maid = resolveMaid(str(msg, "maid", ""))
        val text = str(msg, "text", "")
        if (maid == null || text.isEmpty()) {
            return
        }
        val ticks = if (msg.has("ticks") && msg.get("ticks").isJsonPrimitive)
            msg.get("ticks").asInt else 80
        maid.showBubble(Component.literal(text), ticks)
        MaidDebug.log("桌宠气泡: " + text)
    }

    /** AI 对话回复：头顶气泡 + 主人聊天栏回显。 */
    private fun handleChatReply(msg: JsonObject) {
        val maid = resolveMaid(str(msg, "maid", ""))
        val text = str(msg, "text", "")
        if (text.isEmpty()) {
            return
        }
        if (maid != null) {
            val ticks = max(60, min(200, text.length * 10))
            maid.showBubble(Component.literal(text), ticks)
            val owner = maid.owner
            if (owner is ServerPlayer) {
                owner.sendSystemMessage(Component.literal("女仆：" + text))
            }
        }
        MaidDebug.log("AI 回复: " + text)
    }

    private fun handleAnimation(msg: JsonObject) {
        val maid = resolveMaid(str(msg, "maid", "")) ?: return
        val id: Int
        if (msg.has("id") && msg.get("id").isJsonPrimitive) {
            id = msg.get("id").asInt
        } else {
            val resolved = animId(str(msg, "name", ""))
            if (resolved == null) {
                MaidDebug.log("桌宠动画名未知: " + str(msg, "name", ""))
                return
            }
            id = resolved
        }
        maid.setDebugAnim(id)
        MaidDebug.log("桌宠动画 → id " + id)
    }

    // ---------- 目标女仆定位 ----------

    /** 按 uuid 定位女仆；uuid 为空时退化为「唯一存活女仆」 */
    private fun resolveMaid(uuidText: String): SmartMaidEntity? {
        val alive = aliveMaids()
        if (!uuidText.isBlank()) {
            try {
                val uuid = UUID.fromString(uuidText)
                return alive.firstOrNull { it.uuid == uuid }
            } catch (e: IllegalArgumentException) {
                MaidDebug.log("桌宠给的 maid id 不是合法 UUID: " + uuidText)
            }
            return null
        }
        if (alive.isEmpty()) {
            return null
        }
        if (alive.size > 1) {
            MaidDebug.log("存在 " + alive.size + " 只女仆且指令未指定 maid，取第一只")
        }
        return alive[0]
    }

    private fun seedRoster(srv: MinecraftServer) {
        SESSION.seed(srv.allLevels.asSequence().flatMap { it.allEntities.asSequence() }
            .filterIsInstance<SmartMaidEntity>())
    }

    private fun aliveMaids(): List<SmartMaidEntity> = SESSION.members()

    // ---------- 工具 ----------

    private fun animId(name: String?): Int? {
        if (name.isNullOrEmpty()) {
            return null
        }
        if (animIds == null) {
            val map = HashMap<String, Int>()
            try {
                val cls = Class.forName(
                    "com.oyxdsg.smartmaid.client.animation.MaidAnimManager"
                )
                val names = cls.getMethod("getAnimNames").invoke(null) as Array<*>
                for (i in names.indices) {
                    val n = names[i] as? String
                    if (!n.isNullOrEmpty()) {
                        map[n.lowercase()] = i
                    }
                }
            } catch (t: Throwable) {
                // 专用服务端没有客户端类：只支持数字 id
                SmartMaid.LOGGER.warn("动画名解析不可用（仅支持数字 id）: {}", t.toString())
            }
            animIds = map
        }
        return animIds!![name.lowercase()]
    }

    private fun str(o: JsonObject, key: String, def: String): String {
        return if (o.has(key) && !o.get(key).isJsonNull) o.get(key).asString else def
    }

    /** 每女仆的通道状态 */
    private class Channel {
        val diff = PerceptionDiff()
        var lastPerceptionAt = 0L
    }

    /** WebSocket 监听：只做拼接与线程切换，不碰世界状态。 */
    private class Listener(private val srv: MinecraftServer, val transport: BridgeConfig.WsTransport) : WebSocket.Listener {
        private val buffer = StringBuilder()
        var connection: java.util.concurrent.CompletableFuture<WebSocket>? = null
        @Volatile private var cancelled = false
        @Volatile private var opened: WebSocket? = null

        fun cancel() {
            cancelled = true
            opened?.abort()
            connection?.cancel(true)
        }

        override fun onOpen(ws: WebSocket) {
            opened = ws
            if (cancelled || server !== srv) {
                ws.abort()
                return
            }
            srv.execute { MaidWsClient.onOpen(srv, this, ws) }
        }

        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val text = buffer.toString()
                buffer.setLength(0)
                srv.execute { dispatch(srv, ws, text) }
            }
            ws.request(1)
            return null
        }

        private fun disconnected(ws: WebSocket, reason: String) {
            srv.execute {
                if (server === srv && socket === ws) handleDisconnect(reason)
            }
        }

        override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            disconnected(ws, "peer closed ($statusCode)")
            return null
        }

        override fun onError(ws: WebSocket, error: Throwable) {
            disconnected(ws, "socket error")
        }
    }
}
