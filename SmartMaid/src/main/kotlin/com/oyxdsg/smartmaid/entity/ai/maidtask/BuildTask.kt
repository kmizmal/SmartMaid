package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidBlockPlacer
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * 建造任务（M3）：按目标坐标列表逐格放置方块，列表放完或缺方块结束。
 *
 * <p>放置复用 MaidBlockPlacer（自动换方块到主手、走到目标旁、选支撑面放置）。
 * 蓝图（相对坐标模板）为后续扩展。</p>
 */
class BuildTask(private val positions: List<BlockPos>?) : MaidAITask("build"), Resumable {

    private var index = 0
    private var outOfBlock = false

    // 搭方块执行器（复用 MaidBlockPlacer：自动选支撑面放置）
    private val placer = MaidBlockPlacer()

    override fun canStart(maid: SmartMaidEntity): Boolean = !positions.isNullOrEmpty()

    override fun start(maid: SmartMaidEntity) {
        this.index = 0
        this.outOfBlock = false
        // 换方块到主手交给 MaidBlockPlacer 在 tick 内自动处理
        MaidDebug.log("Build start 位置数=" + (positions?.size ?: 0))
    }

    override fun tick(maid: SmartMaidEntity) {
        val list = this.positions ?: return
        if (this.index >= list.size) {
            return
        }
        val pos = list[this.index]
        // 目标格已有方块 → 跳过
        val state = maid.level().getBlockState(pos)
        if (!state.isAir) {
            this.index++
            return
        }
        // 开始往 pos 搭方块
        if (!this.placer.isActive()) {
            if (!this.placer.begin(maid, pos)) {
                this.outOfBlock = true
                maid.showBubble(Component.literal("没有方块可以放啦"), 60)
                return
            }
        }
        // 驱动搭方块，放上后进入下一个位置
        if (this.placer.tick(maid)) {
            this.index++
        }
    }

    override fun isDone(): Boolean {
        val list = this.positions ?: return true
        return this.index >= list.size || this.outOfBlock
    }

    override fun result(): String {
        val size = this.positions?.size ?: 0
        return if (this.outOfBlock)
            "缺方块，已放置 " + this.index + "/" + size
        else
            "已放置 " + this.index + "/" + size
    }

    override fun forceStop(maid: SmartMaidEntity?) {
        this.placer.abort()
    }

    // ---------- Resumable（Q7） ----------

    override fun saveState(): JsonObject {
        val o = JsonObject()
        o.addProperty("version", stateVersion())
        o.addProperty("index", this.index)
        o.addProperty("outOfBlock", this.outOfBlock)
        if (this.positions != null) {
            o.add("positions", TaskState.posList(this.positions!!))
        }
        return o
    }

    override fun restoreState(s: JsonObject) {
        this.index = if (s.has("index")) s.get("index").getAsInt() else 0
        this.outOfBlock = s.has("outOfBlock") && s.get("outOfBlock").getAsBoolean()
    }

    override fun stateVersion(): Int = 1

    override fun validateState(maid: SmartMaidEntity, s: JsonObject): Boolean =
        !this.positions.isNullOrEmpty()
}
