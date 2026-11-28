package com.oyxdsg.smartmaid.client

import com.mojang.blaze3d.platform.InputConstants
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.client.chat.MaidChatClient
import com.oyxdsg.smartmaid.client.gui.MaidControlScreen
import com.oyxdsg.smartmaid.client.gui.MaidInventoryScreen
import com.oyxdsg.smartmaid.client.gui.MaidMenuScreen
import com.oyxdsg.smartmaid.client.gui.MaidSettingsScreen
import com.oyxdsg.smartmaid.client.gui.MaidTaskScreen
import com.oyxdsg.smartmaid.client.renderer.SmartMaidRenderer
import com.oyxdsg.smartmaid.client.test.MaidClientTest
import com.oyxdsg.smartmaid.compat.MaidCompat
import com.oyxdsg.smartmaid.init.ModEntities
import com.oyxdsg.smartmaid.init.ModMenus
import com.oyxdsg.smartmaid.network.MaidChatPayload
import com.oyxdsg.smartmaid.network.MaidCommandPayload
import com.oyxdsg.smartmaid.network.MaidMenuStatePayload
import com.oyxdsg.smartmaid.network.MaidOpenMenuPayload
import com.oyxdsg.smartmaid.test.MaidErrorSink
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.network.chat.Component

open class SmartMaidClient : ClientModInitializer {
    companion object {
        /**
         * Shift+E：打开女仆背包。
         *
         * **键码必须用 `InputConstants.KEY_*`，不能用 `GLFW.GLFW_KEY_*`** ——
         * 26.3 把 GLFW 换成了 SDL，键值是 **SDL scancode** 而不是 GLFW keysym：
         * `KEY_E` 26.2 是 69（GLFW）、26.3 是 8（SDL）；`KEY_LSHIFT` 是 340 → 225。
         * 用 GLFW 常量在 26.3 上会注册成「F12」之类完全无关的键，且 Shift 判定恒为 false →
         * 按键像“没反应”（实测踩到）。两版都有同名的 `InputConstants.KEY_*`，值就是各自平台编码。
         *
         * 输入类型仍走隔离层：26.2 是 `Type.KEYSYM`，26.3 改名 `Type.KEYBOARD`。
         */
        @JvmField
        val OPEN_INVENTORY: KeyMapping = KeyMapping(
            "key.smartmaid.open_inventory",
            MaidCompat.keyType(),
            InputConstants.KEY_E, KeyMapping.Category.INVENTORY
        )

        /** Shift 键在版本间的编码差异，同样用 `InputConstants.KEY_*` 归掉。 */
        private val SHIFT_KEYS: IntArray = intArrayOf(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT)

        /**
         * 「Shift+E 打开女仆背包」的实际处理逻辑。
         *
         * 从 tick 里抽出来是为了可测：客户端自动化测试能直接调用它，
         * 从而在不模拟真实按键的前提下验证「Shift 判定 + 吞掉原版 E 键 + 发指令」这条链路。
         * 注意本方法**不检查** [KeyMapping.consumeClick]，调用方负责。
         *
         * @return 是否已按 Shift+E 处理（true = 已发开背包指令并吞掉了原版 E）
         */
        @JvmStatic
        fun tryOpenMaidInventory(): Boolean {
            val client = Minecraft.getInstance()
            val shift = MaidCompat.isAnyKeyDown(*SHIFT_KEYS)
            if (!shift) {
                return false
            }
            // 吞掉原版 E 背包键，避免同时打开玩家背包
            client.options.keyInventory.consumeClick()
            if (client.player != null) {
                ClientPlayNetworking.send(MaidCommandPayload(MaidCommandPayload.ACTION_OPEN_INVENTORY))
            }
            return true
        }

        /** 供测试读取：Shift 键在本版本下的编码值（26.2 = 340/344，26.3 = 225/229）。 */
        @JvmStatic
        fun shiftKeyCodes(): IntArray = SHIFT_KEYS.clone()
    }

    override fun onInitializeClient() {
        // 错误接收通道：未捕获异常 + 主动上报 → <gameDir>/smartmaid/errors.jsonl
        MaidErrorSink.install("client")
        register(ModEntities.SMART_MAID, ::SmartMaidRenderer)
        MenuScreens.register(ModMenus.MAID_INVENTORY_MENU, ::MaidInventoryScreen)
        MenuScreens.register(ModMenus.MAID_CONTROL_MENU, ::MaidControlScreen)

        // N1：服务端让打开女仆主菜单 → 客户端开纯 Screen（不挂容器）
        ClientPlayNetworking.registerGlobalReceiver(MaidOpenMenuPayload.TYPE) { payload, _ ->
            Minecraft.getInstance().execute {
                Minecraft.getInstance().setScreenAndShow(MaidMenuScreen(payload.maidId, payload.food))
            }
        }

        // N1 步骤 2/3：菜单状态快照 → 刷新当前活跃的菜单/任务页
        ClientPlayNetworking.registerGlobalReceiver(MaidMenuStatePayload.TYPE) { payload, _ ->
            Minecraft.getInstance().execute {
                MaidTaskScreen.ACTIVE?.updateState(payload.json)
                    ?: MaidSettingsScreen.ACTIVE?.updateState(payload.json)
                    ?: MaidMenuScreen.ACTIVE?.updateState(payload.json)
            }
        }

        // 女仆对话模式：拦截普通聊天文本，转给女仆 AI；//maidchat 开关
        ClientSendMessageEvents.ALLOW_CHAT.register { text ->
            // 保留 Java String.isBlank 的 Character.isWhitespace 语义。
            if (!MaidChatClient.isChatMode() || text.codePoints().allMatch { Character.isWhitespace(it) }) {
                return@register true
            }
            val client = Minecraft.getInstance()
            if (client.player != null) {
                ClientPlayNetworking.send(MaidChatPayload(text))
            }
            false
        }

        // /maidchat：在聊天栏输入，开关女仆对话模式（客户端本地命令，不打扰服务器）
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(ClientCommands.literal("maidchat").executes { ctx ->
                val on = MaidChatClient.toggle()
                ctx.source.sendFeedback(Component.literal(
                    if (on) "已进入女仆对话模式：直接在聊天栏说话即可（输入 /maidchat 退出）"
                    else "已退出女仆对话模式"
                ))
                1
            })
        }

        ClientTickEvents.START_CLIENT_TICK.register { client ->
            while (OPEN_INVENTORY.consumeClick()) {
                tryOpenMaidInventory()
            }
            // 客户端自动化测试（文件驱动，不碰鼠标键盘）；未放测试文件时是空操作
            MaidClientTest.tick(client)
        }

        SmartMaid.LOGGER.info("SmartMaid 客户端初始化完成")
    }
}
