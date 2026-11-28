package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import net.minecraft.world.item.ItemStack
import java.util.function.Predicate

/**
 * 收集任务（M3）：范围内拾取掉落物（女仆 setCanPickUpLoot 自动拾取进背包 0-35），
 * 范围内无目标掉落物结束。
 */
class CollectTask @JvmOverloads constructor(
    private val range: Double,
    private val filter: Predicate<ItemStack>? = null
) : MaidAITask("collect") {

    private var done = false

    override fun canStart(maid: SmartMaidEntity): Boolean = true

    override fun start(maid: SmartMaidEntity) {
        done = false
        MaidDebug.log("Collect start range=$range")
    }

    override fun tick(maid: SmartMaidEntity) {
        val item = MaidActions.findNearestItem(maid, range, filter)
        if (item == null) {
            done = true // 范围内无目标掉落物 → 完成
            return
        }
        // 靠近时解除拾取延迟，女仆原版拾取逻辑收进背包（0-35）
        if (maid.distanceToSqr(item) < 2.0 * 2.0) {
            item.setNoPickUpDelay()
        }
        if (maid.navigation.isDone || maid.tickCount % 20 == 0) {
            MaidActions.navigateTo(maid, item.blockPosition(), 1.0)
        }
    }

    override fun isDone(): Boolean = done

    override fun result(): String = "收集完成"
}
