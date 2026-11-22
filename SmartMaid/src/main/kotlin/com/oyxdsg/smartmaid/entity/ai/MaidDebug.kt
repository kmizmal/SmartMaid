package com.oyxdsg.smartmaid.entity.ai

import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.data.SmartMaidConfig
import net.minecraft.world.item.ItemStack
import org.slf4j.Logger

/**
 * 女仆调试日志输出。所有日志加 [PREFIX] 前缀，供 tools/maid_monitor.ps1 抓取。
 *
 * 开关由配置 config/smartmaid/main.json 的 debug / debugVerbose 控制（默认都 false），
 * 由 SmartMaid.onInitialize 预热；运行期方法仍保留 Java 可见签名（@JvmStatic）。
 */
object MaidDebug {
    private val LOGGER: Logger = SmartMaid.LOGGER
    const val PREFIX = "[SmartMaid-Debug] "

    @Volatile private var enabledFlag = false
    @Volatile private var verboseFlag = false
    @Volatile private var loadedFlag = false

    /** 从配置读取开关（幂等）。 */
    @JvmStatic
    fun reload() {
        enabledFlag = SmartMaidConfig.debug()
        verboseFlag = SmartMaidConfig.debugVerbose()
        loadedFlag = true
    }

    private fun ensureLoaded() {
        if (!loadedFlag) {
            try {
                reload()
            } catch (t: Throwable) {
                // 无 Fabric 运行环境的离线测试：保持默认关闭，不让日志拖崩调用方
                loadedFlag = true
            }
        }
    }

    @JvmStatic
    fun enabled(): Boolean {
        ensureLoaded()
        return enabledFlag
    }

    @JvmStatic
    fun verbose(): Boolean {
        ensureLoaded()
        return enabledFlag && verboseFlag
    }

    @JvmStatic
    fun log(message: String) {
        if (enabled()) {
            LOGGER.info(PREFIX + message)
        }
    }

    @JvmStatic
    fun fmt1(v: Double): String = String.format("%.1f", v)

    /** 物品名（空物品返回 empty）。 */
    @JvmStatic
    fun itemName(stack: ItemStack): String =
        if (stack.isEmpty) "empty" else stack.getItem().descriptionId
}
