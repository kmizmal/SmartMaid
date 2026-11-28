package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.craft.CraftExecutor
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import kotlin.math.max

/**
 * 制作任务（M3）：消耗背包材料直接合成目标物品，count 达成结束。
 *
 * AI 只需报成品目标，女仆自动找配方、凑材料、原地合成（无需工作台）。
 */
class CraftTask(targetStack: ItemStack, countRaw: Int) : MaidAITask("craft") {

    private val target: ItemStack = targetStack.copy()
    private val count: Int = max(1, countRaw)
    private var made = 0
    private var outOfMaterial = false

    override fun canStart(maid: SmartMaidEntity): Boolean = !target.isEmpty

    override fun start(maid: SmartMaidEntity) {
        made = 0
        outOfMaterial = false
        MaidDebug.log("Craft start 目标=" + target.item.descriptionId + " x" + count)
    }

    override fun tick(maid: SmartMaidEntity) {
        if (made >= count) return
        val out = CraftExecutor.craft(maid, target)
        if (out.isEmpty) {
            outOfMaterial = true // 缺材料或无配方
            maid.showBubble(Component.literal("材料不够做 " + target.item.descriptionId), 60)
            return
        }
        made += out.count
    }

    override fun isDone(): Boolean = made >= count || outOfMaterial

    override fun result(): String = if (outOfMaterial) {
        "缺材料，已制作 $made 个"
    } else {
        "已制作 $made 个 " + target.item.descriptionId
    }
}
