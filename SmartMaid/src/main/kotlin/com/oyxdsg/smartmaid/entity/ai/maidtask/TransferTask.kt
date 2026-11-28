package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.slot.ItemSlot
import com.oyxdsg.smartmaid.entity.ai.slot.Slots
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Block

/**
 * 物品转移任务（正交基础指令 transfer）：把物品从槽位 A 移动到槽位 B。
 *
 * 覆盖全部物品操作场景：
 *   - 换主手：transfer inv:N mainhand（= 旧 equip）
 *   - 收纳：transfer mainhand inv:5（= 旧 store）
 *   - 丢出：transfer mainhand world:x,y,z（= 旧 drop）
 *   - 放置方块：transfer mainhand world:x,y,z（BlockItem 自动放置）
 *   - 穿装备：transfer inv:N chest|head|legs|feet
 *   - 放箱子：transfer inv:N container:x,y,z（auto 找空槽）
 *   - 取箱子：transfer container:x,y,z inv:N
 *
 * 地面（world）只能作转移目标（放置/丢出）；地面→背包的拾取由 collect/pickup
 * 指令走过去完成，避免凭空吸物。
 */
open class TransferTask(private val from: ItemSlot?, private val to: ItemSlot?, countParam: Int) : MaidAITask("transfer") {

    private val count: Int = maxOf(1, countParam)
    private var done: Boolean = false
    private var outcome: String = "未执行"

    override fun canStart(maid: SmartMaidEntity): Boolean = this.from != null && this.to != null

    override fun start(maid: SmartMaidEntity) {
        MaidDebug.log("Transfer start from=" + this.from + " to=" + this.to + " count=" + this.count)
    }

    override fun tick(maid: SmartMaidEntity) {
        if (this.done) {
            return
        }
        // 靠近 from/to 所需的位置（world/container 槽需走过去操作）
        val need = nearestReachPos(maid)
        if (need != null && !MaidActions.isWithinReach(maid, need, 2.5)) {
            if (maid.getNavigation().isDone || maid.tickCount % 20 == 0) {
                MaidActions.navigateTo(maid, need, 1.0)
            }
            return
        }
        maid.getNavigation().stop()
        doTransfer(maid)
        this.done = true
    }

    override fun isDone(): Boolean = this.done

    override fun result(): String = this.outcome

    private fun doTransfer(maid: SmartMaidEntity) {
        val from = this.from ?: return
        val to = this.to ?: return
        if (Slots.isWorldSlot(from)) {
            this.outcome = "地面不能作为转移源，拾取请用 collect/pickup 指令"
            MaidDebug.log("Transfer 拒绝: 地面不能作源")
            return
        }
        val extracted = from.extract(maid, this.count)
        if (extracted.isEmpty) {
            this.outcome = "源槽位为空"
            MaidDebug.log("Transfer 失败: " + from + " 为空")
            maid.showBubble(Component.literal(from.toString() + " 是空的"), 60)
            return
        }
        // 目标不接受此物品（如非装备塞盔甲槽）→ 丢到主人身边
        if (!to.canAccept(maid, extracted)) {
            MaidActions.dropToOwner(maid, extracted)
            this.outcome = "目标不接受 " + extracted.item.descriptionId + "，已丢给主人"
            MaidDebug.log("Transfer 掉落: " + from + " -> " + to + " " + this.outcome)
            return
        }
        val left = to.insert(maid, extracted)
        if (left.isEmpty) {
            this.outcome = "已转移 " + extracted.count + " 个 " + extracted.item.descriptionId
            MaidDebug.log("Transfer 完成: " + from + " -> " + to + " " + this.outcome)
            return
        }
        if (left.count == extracted.count) {
            if (to.isSwapAllowed()) {
                // 女仆自身槽位被不同物品占用 → 交换两个槽位
                val displaced = to.extract(maid, Int.MAX_VALUE)
                val leftAfter = to.insert(maid, left)
                if (!leftAfter.isEmpty) {
                    Block.popResource(maid.level(), maid.blockPosition(), leftAfter)
                }
                if (!displaced.isEmpty) {
                    val back = from.insert(maid, displaced)
                    if (!back.isEmpty) {
                        Block.popResource(maid.level(), maid.blockPosition(), back)
                    }
                }
                this.outcome = "已交换：目标槽原物品移到 " + from
                MaidDebug.log("Transfer 交换: " + from + " <-> " + to + " " + this.outcome)
                return
            }
            // 容器放不下（满/无容器）：物品放回源槽，提示，不交换不掉落
            val back = from.insert(maid, left)
            if (!back.isEmpty) {
                Block.popResource(maid.level(), maid.blockPosition(), back)
            }
            this.outcome = "目标放不下（" + to + "），已放回" + from
            MaidDebug.log("Transfer 容器满: " + from + " -> " + to + " " + this.outcome)
            maid.showBubble(Component.literal("箱子放不下，物品已放回"), 60)
            return
        }
        // 部分堆叠（目标同类有剩余空间）→ 剩余放回源
        val back = from.insert(maid, left)
        if (!back.isEmpty) {
            Block.popResource(maid.level(), maid.blockPosition(), back)
        }
        this.outcome = "已转移 " + (extracted.count - left.count) + " 个，剩余 " + left.count + " 个放回"
        MaidDebug.log("Transfer 部分堆叠: " + from + " -> " + to + " " + this.outcome)
    }

    /** from/to 需要靠近的位置中更远的那个（都要靠近时） */
    private fun nearestReachPos(maid: SmartMaidEntity): BlockPos? {
        val a = this.from?.reachPos()
        val b = this.to?.reachPos()
        if (a == null) {
            return b
        }
        if (b == null) {
            return a
        }
        return if (maid.distanceToSqr(a.x.toDouble(), a.y.toDouble(), a.z.toDouble()) >
            maid.distanceToSqr(b.x.toDouble(), b.y.toDouble(), b.z.toDouble())
        ) a else b
    }
}
