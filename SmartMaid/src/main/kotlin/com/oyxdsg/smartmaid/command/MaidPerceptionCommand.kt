package com.oyxdsg.smartmaid.command

import com.mojang.brigadier.CommandDispatcher
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.permissions.PermissionCheck
import net.minecraft.world.entity.Entity

object MaidPerceptionCommand {
    @JvmStatic
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, permission: PermissionCheck) {
        dispatcher.register(Commands.literal("maidperception").requires(Commands.hasPermission(permission))
            .executes { ctx -> dump(ctx.source) })
    }

    private fun dump(source: CommandSourceStack): Int {
        return try {
            val player = source.playerOrException
            val level = player.level() as? ServerLevel ?: return 0
            for (entity: Entity in level.allEntities) {
                if (entity is SmartMaidEntity && entity.isOwnedBy(player)) {
                    val module = entity.perceptionModule
                    module.tick()
                    val p = module.current() ?: run { source.sendFailure(Component.literal("感知快照尚未生成")); return 0 }
                    val json = p.toJson()
                    MaidDebug.log("手动感知快照: $json")
                    val self = p.self()
                    val status = self.getAsJsonObject("status")
                    val task = if (status.has("task_id") && !status.get("task_id").isJsonNull) status.get("task_id").asString else "无"
                    source.sendSuccess({ Component.literal("感知快照已输出到日志（大小 ${json.length}B）: pos=${self.get("pos")} health=${self.get("health")} task=$task 事件数=${p.events().size()}") }, false)
                    return 1
                }
            }
            source.sendFailure(Component.literal("未找到你的女仆"))
            0
        } catch (e: Exception) {
            SmartMaid.LOGGER.error("maidperception 执行失败", e)
            source.sendFailure(Component.literal("执行失败: $e"))
            0
        }
    }
}
