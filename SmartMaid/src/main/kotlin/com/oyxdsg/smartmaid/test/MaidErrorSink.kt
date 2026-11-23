package com.oyxdsg.smartmaid.test

import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.function.BiConsumer
import net.fabricmc.loader.api.FabricLoader

/**
 * 统一的**错误接收通道**：把「我们主动上报的失败」和「未捕获异常」写到
 * `<gameDir>/smartmaid/errors.jsonl`（JSON Lines，一行一条，append-only）。
 *
 * 为什么要它：整合包环境里出问题时，唯一能拿到的是 `latest.log`，而它是 GBK、
 * 混着几千行别人的日志、且异常经常被 mod 框架吞掉或包一层。这个通道提供
 * **机器可读、只含本模组信息、可跨会话累积** 的载体 —— 配合
 * `tools/read_test_report.py` 一条命令就能看全。
 *
 * 写入策略：**绝不抛异常、绝不阻塞**。任何 IO 失败只打一条 WARN 就放弃，
 * 避免"上报本身把游戏搞崩"。文件超大（> 2 MiB）时自动轮转成 `errors.1.jsonl`。
 */
class MaidErrorSink private constructor() {
    companion object {
        private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private const val MAX_BYTES = 2L * 1024 * 1024

        /** 日志器：由 SmartMaid 主类注入，避免本包依赖主类造成环。 */
        @Volatile
        private var fallbackLog: BiConsumer<String, Throwable>? = null

        private var file: Path? = null
        private var installed = false
        private var count = 0

        /** 由主类注册一个「实在写不进去时的兜底日志器」。 */
        @JvmStatic
        fun setFallbackLogger(logger: BiConsumer<String, Throwable>?) {
            fallbackLog = logger
        }

        @JvmStatic
        fun gameDir(): Path = FabricLoader.getInstance().gameDir

        // 保留原 Java static synchronized 共享且可重入的类监视器，而不是锁 Companion。
        /** `<gameDir>/smartmaid/errors.jsonl` */
        @JvmStatic
        fun errorsFile(): Path = synchronized(MaidErrorSink::class.java) {
            file ?: gameDir().resolve("smartmaid").resolve("errors.jsonl").also { file = it }
        }

        /**
         * 安装全局未捕获异常处理器（幂等）。
         *
         * 会**链式调用**原有处理器，不改变崩溃行为 —— 只多一条记录。
         *
         * @param role 进程角色，仅用于会话头（如 `client` / `server`）
         */
        @JvmStatic
        fun install(role: String?) {
            synchronized(MaidErrorSink::class.java) {
                note("session", "$role 错误通道就绪 (mc=${mcVersion()})")
                if (installed) return
                installed = true
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                    report("uncaught@${thread.name}", "未捕获异常", error)
                    previous?.uncaughtException(thread, error)
                }
            }
        }

        @JvmStatic
        fun mcVersion(): String = try {
            FabricLoader.getInstance().getModContainer("minecraft")
                .map { it.metadata.version.friendlyString }.orElse("?")
        } catch (_: Throwable) {
            "?"
        }

        @JvmStatic
        fun modVersion(): String = try {
            FabricLoader.getInstance().getModContainer("smartmaid")
                .map { it.metadata.version.friendlyString }.orElse("?")
        } catch (_: Throwable) {
            "?"
        }

        /** 记一条信息级记录（不是错误，但需要留痕，如"测试开始""通道就绪"）。 */
        @JvmStatic
        fun note(tag: String?, message: String?) {
            write("INFO", tag, message, null)
        }

        /** 记一条错误（主动上报，例如某个功能失败了但被我们捕获）。 */
        @JvmStatic
        fun report(tag: String?, message: String?, error: Throwable?) {
            write("ERROR", tag, message, error)
        }

        /** 记一条警告（可疑但不算失败）。 */
        @JvmStatic
        fun warn(tag: String?, message: String?) {
            write("WARN", tag, message, null)
        }

        private fun write(level: String, tag: String?, message: String?, error: Throwable?) {
            synchronized(MaidErrorSink::class.java) {
                try {
                    val f = errorsFile()
                    Files.createDirectories(f.parent)
                    rotateIfNeeded(f)
                    val sb = StringBuilder(512)
                    sb.append("{\"ts\":\"").append(esc(LocalDateTime.now().format(TS))).append('"')
                        .append(",\"level\":\"").append(level).append('"')
                        .append(",\"tag\":\"").append(esc(tag)).append('"')
                        .append(",\"mc\":\"").append(esc(mcVersion())).append('"')
                        .append(",\"mod\":\"").append(esc(modVersion())).append('"')
                        .append(",\"thread\":\"").append(esc(Thread.currentThread().name)).append('"')
                        .append(",\"msg\":\"").append(esc(message)).append('"')
                    if (error != null) {
                        val sw = StringWriter()
                        error.printStackTrace(PrintWriter(sw))
                        sb.append(",\"exception\":\"").append(esc(error.javaClass.name)).append('"')
                            .append(",\"stack\":\"").append(esc(sw.toString())).append('"')
                    }
                    sb.append("}\n")
                    Files.writeString(
                        f, sb.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND
                    )
                    count++
                } catch (t: Throwable) {
                    val fb = fallbackLog
                    fb?.accept("[MaidErrorSink] 上报失败: $tag / $message", t)
                }
            }
        }

        @Throws(IOException::class)
        private fun rotateIfNeeded(f: Path) {
            if (Files.exists(f) && Files.size(f) > MAX_BYTES) {
                val old = f.resolveSibling("errors.1.jsonl")
                Files.deleteIfExists(old)
                Files.move(f, old)
            }
        }

        /** 最小 JSON 字符串转义（只处理必需字符，避免引入 gson 依赖）。 */
        private fun esc(s: String?): String {
            if (s == null) return ""
            val b = StringBuilder(s.length + 16)
            for (c in s) {
                when (c) {
                    '"' -> b.append("\\\"")
                    '\\' -> b.append("\\\\")
                    '\n' -> b.append("\\n")
                    '\r' -> b.append("\\r")
                    '\t' -> b.append("\\t")
                    else -> {
                        if (c < ' ') {
                            b.append(String.format("\\u%04x", c.code))
                        } else {
                            b.append(c)
                        }
                    }
                }
            }
            return b.toString()
        }
    }
}
