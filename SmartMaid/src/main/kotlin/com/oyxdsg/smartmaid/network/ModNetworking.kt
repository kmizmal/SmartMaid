package com.oyxdsg.smartmaid.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.data.MaidDataManager
import com.oyxdsg.smartmaid.data.MaidSettings
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidAIBridge
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidCommandResult
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidWsClient
import com.oyxdsg.smartmaid.gui.MaidInventoryMenuProvider
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/**
 * 网络注册：女仆 GUI 动作包（C2S）+ 服务端接收处理。
 * 服务端按发送玩家查找其唯一的女仆执行动作。
 */
class ModNetworking private constructor() {
    companion object {
        @JvmStatic
        fun register() {
            // Fabric 在安装收包处理器前会检查类型是否已注册。
            val serverbound = PayloadTypeRegistry.serverboundPlay()
            serverbound.register(MaidCommandPayload.TYPE, MaidCommandPayload.STREAM_CODEC)
            serverbound.register(MaidSettingsPayload.TYPE, MaidSettingsPayload.STREAM_CODEC)
            serverbound.register(MaidChatPayload.TYPE, MaidChatPayload.STREAM_CODEC)
            serverbound.register(MaidMenuActionPayload.TYPE, MaidMenuActionPayload.STREAM_CODEC)

            val clientbound = PayloadTypeRegistry.clientboundPlay()
            clientbound.register(MaidOpenMenuPayload.TYPE, MaidOpenMenuPayload.STREAM_CODEC)
            clientbound.register(MaidMenuStatePayload.TYPE, MaidMenuStatePayload.STREAM_CODEC)

            ServerPlayNetworking.registerGlobalReceiver(MaidCommandPayload.TYPE) { payload, context ->
                val player = context.player()
                player.level().getServer().execute command@{
                    val level: Level = player.level()
                    if (level !is ServerLevel) return@command
                    val maid = findOwnerMaid(level, player)
                    if (maid == null) {
                        SmartMaid.LOGGER.debug("GUI 动作未找到玩家 {} 的女仆", player.getName().getString())
                        return@command
                    }
                    when (payload.action) {
                        MaidCommandPayload.ACTION_TOGGLE_SIT -> {
                            // 坐/站统一切换：落地吸附 + 移动锁定都在 setOrderedToSit 内处理
                            maid.setOrderedToSit(!maid.isOrderedToSit())
                        }
                        MaidCommandPayload.ACTION_RECALL -> maid.teleportToOwner()
                        MaidCommandPayload.ACTION_CLEAR_TARGET -> maid.setTarget(null)
                        MaidCommandPayload.ACTION_OPEN_INVENTORY -> {
                            player.openMenu(MaidInventoryMenuProvider(maid))
                        }
                        else -> SmartMaid.LOGGER.warn("未知女仆动作: {}", payload.action)
                    }
                }
            }

            ServerPlayNetworking.registerGlobalReceiver(MaidSettingsPayload.TYPE) { payload, context ->
                val player = context.player()
                player.level().getServer().execute settings@{
                    val level: Level = player.level()
                    if (level !is ServerLevel) return@settings
                    val maid = findOwnerMaid(level, player) ?: return@settings
                    // 服务端权威：只接受字段名 + 档位索引，写入后落盘并即时应用到女仆
                    maid.getSettings().applyField(payload.field, payload.value)
                    MaidSettings.save(player.getUUID())
                    maid.getSettings().applyToMaid(maid)
                    MaidDebug.log("设置更新 ${payload.field}=${payload.value}")
                    sendMenuState(player, maid) // 回推新值（设置页实时刷新）
                }
            }

            ServerPlayNetworking.registerGlobalReceiver(MaidChatPayload.TYPE) { payload, context ->
                val player = context.player()
                player.level().getServer().execute chat@{
                    val level: Level = player.level()
                    if (level !is ServerLevel) return@chat
                    val maid = findOwnerMaid(level, player)
                    if (maid == null) {
                        player.sendSystemMessage(Component.literal("你还没有召唤女仆"))
                        return@chat
                    }
                    // 26.2 客户端聊天组件为 private，这里用系统消息回显玩家发言
                    player.sendSystemMessage(Component.literal("你：${payload.text}"))
                    MaidWsClient.sendChat(maid, payload.text)
                }
            }

            // 菜单动作（N1）：转成 queue 指令走 MaidAIBridge，与 /maidai 同一条路径
            ServerPlayNetworking.registerGlobalReceiver(MaidMenuActionPayload.TYPE) { payload, context ->
                val player = context.player()
                player.level().getServer().execute menuAction@{
                    val level: Level = player.level()
                    if (level !is ServerLevel) return@menuAction
                    val maid = findOwnerMaid(level, player) ?: return@menuAction
                    val json = if (payload.op == "enqueue" && payload.cmd.isNotEmpty()) {
                        // 菜单下发新任务：主人显式（P2）→ 插队首
                        val req = JsonObject()
                        req.addProperty("id", "menu")
                        req.addProperty("cmd", payload.cmd)
                        req.add("params", parseParams(payload.params))
                        req.addProperty("queue", true)
                        req.addProperty("priority", "owner")
                        req.toString()
                    } else {
                        val params = JsonObject()
                        params.addProperty("op", payload.op)
                        if (payload.op == "stopGroup") {
                            // 整组停止：把 id 字段当组 id 传入
                            params.addProperty("group", payload.id)
                        } else {
                            if (payload.id.isNotEmpty()) {
                                params.addProperty("id", payload.id)
                            }
                            if (payload.kind.isNotEmpty()) {
                                params.addProperty("kind", payload.kind)
                            }
                            if (payload.value != 0) {
                                params.addProperty("to", payload.value)
                            }
                        }
                        val req = JsonObject()
                        req.addProperty("id", "menu")
                        req.addProperty("cmd", "queue")
                        req.add("params", params)
                        req.toString()
                    }
                    val result = MaidAIBridge.execute(maid, json)
                    // 动作后立刻回发状态 + 回执（N1 步骤 2/3）；周期查询不带回执，避免刷屏
                    sendMenuState(player, maid, if (payload.op == "query") null else result)
                }
            }

            // 玩家断线时保存女仆数据（含任务队列）→ Q8 持久化不丢"退出前刚加的任务"
            ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
                val player = handler.getPlayer()
                val level: Level = player.level()
                if (level is ServerLevel) {
                    val maid = findOwnerMaid(level, player)
                    if (maid != null) {
                        MaidDataManager.save(maid)
                    }
                }
            }
        }

        private fun parseParams(s: String): JsonObject = try {
            JsonParser.parseString(s).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }

        /** 向玩家发送一次菜单状态快照（S2C）：队列 + 战斗 + 饱食度。 */
        @JvmStatic
        fun sendMenuState(player: ServerPlayer, maid: SmartMaidEntity) {
            sendMenuState(player, maid, null)
        }

        /** 带回执的状态快照：回执附带 ok + 简短文案（供客户端 toast）。 */
        @JvmStatic
        fun sendMenuState(player: ServerPlayer, maid: SmartMaidEntity, result: MaidCommandResult?) {
            val st = maid.getMaidTaskManager().snapshot()
            st.addProperty("food", maid.getMaidFood().getFoodLevel())
            st.add("settings", maid.getSettings().toJson())
            if (result != null) {
                st.addProperty("receipt_ok", result.ok)
                st.addProperty("receipt_msg", if (result.ok) result.step else errOf(result))
            }
            ServerPlayNetworking.send(player, MaidMenuStatePayload(st.toString()))
        }

        private fun errOf(r: MaidCommandResult): String {
            if (r.result.has("error")) {
                return r.result.get("error").asString
            }
            if (r.result.has("reason")) {
                return r.result.get("reason").asString
            }
            return "失败"
        }

        /** 查找某玩家名下的女仆（每玩家仅一只） */
        private fun findOwnerMaid(level: ServerLevel, player: ServerPlayer): SmartMaidEntity? {
            for (entity in level.getAllEntities()) {
                if (entity is SmartMaidEntity && entity.isOwnedBy(player)) {
                    return entity
                }
            }
            return null
        }
    }
}
