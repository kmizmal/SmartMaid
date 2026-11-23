package com.oyxdsg.smartmaid.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.maidtask.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.IdentifierArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.permissions.PermissionCheck
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

object MaidTaskCommand {
    @JvmStatic fun register(dispatcher: CommandDispatcher<CommandSourceStack>, permission: PermissionCheck) {
        val root = Commands.literal("maidtasks").requires(Commands.hasPermission(permission))
            .then(Commands.literal("attack").executes { dispatch(it.source, AttackTask(12)) }.then(Commands.argument("range", IntegerArgumentType.integer(1, 64)).executes { dispatch(it.source, AttackTask(IntegerArgumentType.getInteger(it, "range"))) }))
            .then(Commands.literal("guard").executes { dispatch(it.source, GuardTask(16, false)) }.then(Commands.argument("range", IntegerArgumentType.integer(1, 64)).executes { dispatch(it.source, GuardTask(IntegerArgumentType.getInteger(it, "range"), false)) }))
            .then(Commands.literal("feed").executes { dispatch(it.source, FeedTask()) })
            .then(Commands.literal("eat").executes { dispatch(it.source, EatTask(null)) }.then(Commands.argument("item", IdentifierArgument.id()).executes { val item = resolveItem(it.source, IdentifierArgument.getId(it, "item")); if (item == null) 0 else dispatch(it.source, EatTask(item)) }))
            .then(Commands.literal("mine").executes { dispatch(it.source, MineTask(ownerMaidPos(it.source), 12, 8)) }.then(Commands.argument("pos", BlockPosArgument.blockPos()).executes { dispatch(it.source, MineTask(BlockPosArgument.getLoadedBlockPos(it, "pos"), 4, 8)) }))
            .then(Commands.literal("farm").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes { dispatch(it.source, FarmTask(BlockPosArgument.getLoadedBlockPos(it, "pos"), 4)) }))
            .then(Commands.literal("build").then(Commands.argument("pos", BlockPosArgument.blockPos()).then(Commands.argument("height", IntegerArgumentType.integer(1, 64)).executes { dispatch(it.source, BuildTask(stackUp(BlockPosArgument.getLoadedBlockPos(it, "pos"), IntegerArgumentType.getInteger(it, "height")))) })))
            .then(Commands.literal("collect").executes { dispatch(it.source, CollectTask(8.0)) }.then(Commands.argument("range", IntegerArgumentType.integer(1, 32)).executes { dispatch(it.source, CollectTask(IntegerArgumentType.getInteger(it, "range").toDouble())) }))
            .then(Commands.literal("craft").then(Commands.argument("item", IdentifierArgument.id()).executes { craft(it.source, IdentifierArgument.getId(it, "item"), 1) }))
            .then(Commands.literal("smelt").then(Commands.argument("item", IdentifierArgument.id()).executes { smelt(it.source, IdentifierArgument.getId(it, "item"), 1) }))
            .then(Commands.literal("move").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes { dispatch(it.source, MoveToTask(BlockPosArgument.getLoadedBlockPos(it, "pos"), 1.0, 1.5)) }))
            .then(Commands.literal("drop").executes { drop(it.source, 1) }.then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).executes { drop(it.source, IntegerArgumentType.getInteger(it, "count")) }))
            .then(Commands.literal("pickup").executes { dispatch(it.source, CollectTask(4.0)) })
            .then(Commands.literal("sit").executes { dispatch(it.source, OneShotTask("sit", "已切换坐姿") { withMaid(it.source) { maid -> maid.setOrderedToSit(!maid.isOrderedToSit) } }) })
            .then(Commands.literal("stop").executes { dispatch(it.source, OneShotTask("stop", "已停止") { withMaid(it.source) { maid -> maid.navigation.stop(); maid.maidTaskManager.cancel() } }) })
            .then(Commands.literal("cancel").executes { cancel(it.source) })
            .then(Commands.literal("status").executes { status(it.source) })
        dispatcher.register(root)
    }

    private fun craft(source: CommandSourceStack, id: Identifier, count: Int): Int { val item = resolveItem(source, id) ?: return 0; return dispatch(source, CraftTask(ItemStack(item), count)) }
    private fun smelt(source: CommandSourceStack, id: Identifier, count: Int): Int { val item = resolveItem(source, id) ?: return 0; return dispatch(source, SmeltTask(ItemStack(item), count)) }
    private fun resolveItem(source: CommandSourceStack, id: Identifier): Item? { val item = BuiltInRegistries.ITEM.get(id).map { it.value() }.orElse(null); if (item == null) source.sendFailure(Component.literal("未找到物品: $id")); return item }
    private fun drop(source: CommandSourceStack, count: Int) = dispatch(source, OneShotTask("drop", "已丢出 $count 个") { withMaid(source) { maid -> val hand = maid.mainHandItem; if (!hand.isEmpty) { MaidActions.dropToOwner(maid, hand.split(minOf(count, hand.count))); maid.syncInventoryArmor() } } })
    private fun cancel(source: CommandSourceStack): Int { val maid = findOwnerMaid(source) ?: return source.sendFailure(Component.literal("没有找到你的女仆")).let { 0 }; maid.maidTaskManager.cancel(); source.sendSuccess({ Component.literal("已取消女仆当前任务") }, true); return 1 }
    private fun status(source: CommandSourceStack): Int { val maid = findOwnerMaid(source) ?: return source.sendFailure(Component.literal("没有找到你的女仆")).let { 0 }; val current = maid.maidTaskManager.currentTask(); source.sendSuccess({ Component.literal(current?.let { "女仆当前任务: ${it.taskId()}" } ?: "女仆当前无 AI 任务") }, true); return 1 }
    private fun dispatch(source: CommandSourceStack, task: MaidAITask): Int { val maid = findOwnerMaid(source) ?: return source.sendFailure(Component.literal("没有找到你的女仆")).let { 0 }; val ok = maid.maidTaskManager.setTask(task); source.sendSuccess({ Component.literal(if (ok) "任务已下发: ${task.taskId()}" else "任务前置条件不满足: ${task.taskId()}") }, true); return if (ok) 1 else 0 }
    private fun withMaid(source: CommandSourceStack, action: (SmartMaidEntity) -> Unit) { findOwnerMaid(source)?.let(action) ?: source.sendFailure(Component.literal("没有找到你的女仆")) }
    private fun stackUp(pos: BlockPos, height: Int) = (0 until height).map { pos.offset(0, it, 0) }
    private fun findOwnerMaid(source: CommandSourceStack): SmartMaidEntity? = try { val player = source.playerOrException; val level =
        player.level(); level.allEntities.firstOrNull { it is SmartMaidEntity && it.isOwnedBy(player) } as? SmartMaidEntity } catch (e: Exception) { SmartMaid.LOGGER.error("maidtasks 查找女仆失败", e); null }
    private fun ownerMaidPos(source: CommandSourceStack) = findOwnerMaid(source)?.blockPosition() ?: BlockPos.containing(source.position)
}
