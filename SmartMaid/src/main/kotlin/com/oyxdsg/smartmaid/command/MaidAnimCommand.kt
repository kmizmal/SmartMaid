package com.oyxdsg.smartmaid.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.client.animation.MaidAnimManager
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.permissions.PermissionCheck
import net.minecraft.world.entity.Entity

object MaidAnimCommand {
    private val ANIM_IDS = HashMap<String, Int>().apply {
        MaidAnimManager.getAnimNames().forEachIndexed { i, name -> if (name.isNotEmpty()) put(name, i) }
    }

    @JvmStatic
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, permission: PermissionCheck) {
        dispatcher.register(Commands.literal("maidanim").requires(Commands.hasPermission(permission))
            .then(Commands.argument("name", StringArgumentType.word())
                .executes { ctx -> setAnim(ctx.source, StringArgumentType.getString(ctx, "name")) })
            .then(Commands.argument("id", IntegerArgumentType.integer(0, 99))
                .executes { ctx -> setAnimInt(ctx.source, IntegerArgumentType.getInteger(ctx, "id")) }))
    }

    private fun setAnim(source: CommandSourceStack, name: String): Int {
        val id = ANIM_IDS[name.lowercase()]
        if (id == null) {
            source.sendFailure(Component.literal("未知动作: $name（可用: ${ANIM_IDS.keys}）"))
            return 0
        }
        return setAnimInt(source, id)
    }

    private fun setAnimInt(source: CommandSourceStack, id: Int): Int {
        return try {
            val player = source.playerOrException
            val level = player.level() as? ServerLevel ?: return 0
            for (entity: Entity in level.allEntities) {
                if (entity is SmartMaidEntity && entity.isOwnedBy(player)) {
                    entity.setDebugAnim(id)
                    source.sendSuccess({ Component.literal("女仆动画已设为 $id" + if (id == 0) "（清除）" else "：${MaidAnimManager.getAnimNames()[id]}") }, false)
                    return 1
                }
            }
            source.sendFailure(Component.literal("未找到你的女仆"))
            0
        } catch (e: Exception) {
            SmartMaid.LOGGER.error("maidanim 执行失败", e)
            source.sendFailure(Component.literal("执行失败: $e"))
            0
        }
    }
}
