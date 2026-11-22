package com.oyxdsg.smartmaid.command

import com.mojang.brigadier.CommandDispatcher
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.data.MaidDataManager
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidWsClient
import com.oyxdsg.smartmaid.init.ModEntities
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.PermissionCheck
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason

object SummonMaidCommand {
    @JvmStatic
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, permission: PermissionCheck) {
        dispatcher.register(Commands.literal("summonmaid").requires(Commands.hasPermission(permission))
            .executes { ctx -> summon(ctx.source, ctx.source.playerOrException) })
    }

    private fun summon(source: CommandSourceStack, player: ServerPlayer): Int = try {
        doSummon(source, player)
    } catch (e: Exception) {
        SmartMaid.LOGGER.error("summonmaid 执行失败", e)
        source.sendFailure(Component.literal("女仆召唤失败: $e"))
        0
    }

    private fun doSummon(source: CommandSourceStack, player: ServerPlayer): Int {
        val level: ServerLevel = player.level()
        for (entity: Entity in level.allEntities) {
            if (entity is SmartMaidEntity && player.uuid == entity.ownerReference?.uuid) {
                source.sendSuccess({ Component.literal("该玩家已有一只女仆，不能重复召唤") }, false)
                return 0
            }
        }
        val maid = ModEntities.SMART_MAID.create(level, EntitySpawnReason.COMMAND) ?: return 0
        maid.setPos(player.x, player.y, player.z)
        maid.tame(player)
        maid.setOrderedToSit(false)
        level.addFreshEntity(maid)
        MaidDataManager.load(maid)
        maid.settings
        MaidWsClient.notifyPresence(maid, true)
        maid.showBubble(Component.literal("主人好呀，我来了~"), 80)
        source.sendSuccess({ Component.literal("女仆已召唤。聊天栏输入 /maidchat 进入对话模式（再输一次退出），或按住 Y 语音对话。") }, true)
        return 1
    }
}
