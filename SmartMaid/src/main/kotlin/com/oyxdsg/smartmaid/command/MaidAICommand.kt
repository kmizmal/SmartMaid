package com.oyxdsg.smartmaid.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidAIBridge
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.PermissionCheck
import net.minecraft.world.entity.Entity

object MaidAICommand {
    @JvmStatic
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, permission: PermissionCheck) {
        dispatcher.register(Commands.literal("maidai").requires(Commands.hasPermission(permission))
            .then(Commands.argument("json", StringArgumentType.greedyString())
                .executes { ctx -> exec(ctx.source, StringArgumentType.getString(ctx, "json")) }))
    }

    private fun exec(source: CommandSourceStack, json: String): Int {
        return try {
            val player = source.playerOrException
            val level = player.level()
            for (entity: Entity in level.allEntities) {
                if (entity is SmartMaidEntity && entity.isOwnedBy(player)) {
                    val result = MaidAIBridge.execute(entity, json)
                    MaidDebug.log("maidai 指令: $json")
                    MaidDebug.log("maidai 回执: ${result.toJson()}")
                    source.sendSuccess({ Component.literal(result.toJson()) }, false)
                    return 1
                }
            }
            source.sendFailure(Component.literal("未找到你的女仆"))
            0
        } catch (e: Exception) {
            SmartMaid.LOGGER.error("maidai 执行失败", e)
            source.sendFailure(Component.literal("执行失败: $e"))
            0
        }
    }
}
