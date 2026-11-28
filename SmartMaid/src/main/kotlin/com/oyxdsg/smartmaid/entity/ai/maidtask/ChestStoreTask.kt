package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.slot.Slots
import net.minecraft.core.BlockPos

/**
 * 存入箱子（一个原子队列项，DESIGN_MAID_MENU_N1 §3.5）：内部两步「开箱 → 放入主手物品」。
 *
 * <p>打包成一项的原因：拆成 chestopen+chestput 两项时，前一步失败后一步必然失败，
 * 需要引入"组失败"复杂度；打包后"一个项 = 一个原子语义单位"，失败策略干净。</p>
 */
class ChestStoreTask(private val hint: BlockPos?, count: Int) : MaidAITask("cheststore") {

    private val count = maxOf(1, count)
    private val open = ChestOpenTask(hint)
    private var put: TransferTask? = null
    private var opened = false
    private var failed = false

    override fun canStart(maid: SmartMaidEntity): Boolean = this.hint != null

    override fun start(maid: SmartMaidEntity) {
        this.opened = false
        this.failed = false
        this.put = null
        this.open.start(maid)
    }

    override fun tick(maid: SmartMaidEntity) {
        if (this.failed) {
            return
        }
        if (!this.opened) {
            this.open.tick(maid)
            if (this.open.isDone()) {
                if (maid.getOpenedContainer() == null) {
                    this.failed = true
                    MaidDebug.log("ChestStore 失败：未能打开箱子")
                    return
                }
                this.opened = true
            }
            return
        }
        if (this.put == null) {
            val cp = maid.getOpenedContainer() ?: run {
                this.failed = true
                return
            }
            this.put = TransferTask(Slots.mainhand(), Slots.container(cp, -1), this.count)
            this.put!!.start(maid)
        }
        this.put!!.tick(maid)
    }

    override fun isDone(): Boolean = this.failed || (this.put != null && this.put!!.isDone())

    override fun forceStop(maid: SmartMaidEntity?) {
        this.put?.forceStop(maid)
    }

    override fun result(): String {
        return if (this.failed) "存箱失败（箱子不可达）" else "已存入箱子"
    }
}
