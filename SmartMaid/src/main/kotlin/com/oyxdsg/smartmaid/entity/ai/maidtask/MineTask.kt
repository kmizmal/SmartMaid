package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidBlockBreaker
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionBlockUtil
import net.minecraft.core.BlockPos
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.state.BlockState

/**
 * 挖矿任务（M3）：换镐 → 区域搜矿物 → 有挖掘过程的挖矿（进度裂纹 + 按硬度耗时）→
 * 掉落物直接进背包，count 达成或区域无矿结束。
 *
 * 26.2 移除了 OreBlock（已反编译确认），矿物判定改用矿石标签
 * （coal_ores / iron_ores / gold_ores / diamond_ores 等，均存在于原版数据包）。
 */
open class MineTask(private val center: BlockPos?, private val range: Int, countParam: Int) :
    MaidAITask("mine"), Resumable {

    private val count: Int = maxOf(1, countParam)
    private var mined: Int = 0
    private var current: BlockPos? = null
    private var noOre: Boolean = false
    /** 已被判定「被遮挡挖不到」的矿位（跳过，不再反复尝试） */
    private val skipped: MutableSet<BlockPos> = HashSet()

    // 挖掘执行器（复用 MaidBlockBreaker：走到旁 → 裂纹耗时 → 破坏进背包）
    private val breaker = MaidBlockBreaker()

    override fun canStart(maid: SmartMaidEntity): Boolean = this.center != null

    override fun start(maid: SmartMaidEntity) {
        this.mined = 0
        this.current = null
        this.noOre = false
        this.skipped.clear()
        // 换镐：背包找镐类工具；没有镐则徒手继续（不阻塞任务）
        MaidActions.equipFromBackpack(maid) { stack ->
            stack.`is`(Items.DIAMOND_PICKAXE) ||
                stack.`is`(Items.IRON_PICKAXE) || stack.`is`(Items.STONE_PICKAXE) ||
                stack.`is`(Items.WOODEN_PICKAXE) || stack.`is`(Items.GOLDEN_PICKAXE) ||
                stack.`is`(Items.NETHERITE_PICKAXE)
        }
        MaidDebug.log("Mine start center=" + center + " range=" + range + " count=" + count)
    }

    override fun tick(maid: SmartMaidEntity) {
        if (this.mined >= this.count || this.noOre) {
            return
        }
        // 找下一个矿并交给挖掘执行器
        if (!this.breaker.isActive()) {
            val cur = this.current
            if (cur == null || maid.level().getBlockState(cur).isAir) {
                val next = findNearestOre(maid)
                this.current = next
                if (next == null) {
                    this.noOre = true
                    return
                }
            }
            val target = this.current ?: return
            if (!this.breaker.begin(maid, target)) {
                // 不可破坏方块 → 跳过，下次 tick 找下一个矿
                this.current = null
                return
            }
        }
        // 每 tick 驱动挖掘（含寻路/裂纹/破坏），完成后计数
        if (this.breaker.tick(maid)) {
            this.mined++
            this.current = null
        } else if (!this.breaker.isActive()) {
            // 挖掘被中止（目标被遮挡挖不到 / 中断）→ 记入跳过，换下一个矿
            this.current?.let { this.skipped.add(it) }
            this.current = null
        }
    }

    override fun isDone(): Boolean = this.mined >= this.count || this.noOre

    override fun result(): String {
        // 有矿但一块没挖到（够不到 / 没方块搭高 / 被挡）→ 如实回报失败，不假报"完成"
        if (this.mined == 0 && !this.skipped.isEmpty()) {
            return "没挖到矿（够不到或无法搭高）"
        }
        return "已挖 " + this.mined + " 个矿物"
    }

    /** 结构化结果（脚本变量绑定用：mine as ore → $ore.collected）。 */
    override fun resultJson(): JsonObject {
        val out = JsonObject()
        out.addProperty("collected", this.mined)
        out.addProperty("target", this.count)
        return out
    }

    override fun forceStop(maid: SmartMaidEntity?) {
        this.breaker.abort(maid) // 清理裂纹并复位
    }

    override fun progressToken(): String = "mined=" + this.mined

    // ---------- Resumable（Q7） ----------

    override fun saveState(): JsonObject {
        val o = JsonObject()
        o.addProperty("version", stateVersion())
        this.center?.let { o.add("center", TaskState.pos(it)) }
        o.addProperty("range", this.range)
        o.addProperty("count", this.count)
        o.addProperty("mined", this.mined)
        o.addProperty("noOre", this.noOre)
        this.current?.let { o.add("current", TaskState.pos(it)) }
        o.add("skipped", TaskState.posList(this.skipped))
        return o
    }

    override fun restoreState(state: JsonObject?) {
        if (state == null) return
        this.mined = if (state.has("mined")) state.get("mined").getAsInt() else 0
        this.noOre = state.has("noOre") && state.get("noOre").getAsBoolean()
        this.current = TaskState.readPos(state.get("current"))
        this.skipped.clear()
        this.skipped.addAll(TaskState.readPosList(state.get("skipped")))
    }

    override fun stateVersion(): Int = 1

    override fun validateState(maid: SmartMaidEntity?, state: JsonObject?): Boolean =
        this.center != null && maid != null

    /** 区域内找最近的、尚未判定挖不到的矿石方块。
     *  视线遮挡判定在女仆走到矿旁后由挖掘执行器做（对齐玩家：准星射线被挡就点不到矿）——
     *  远处视角会误判，先走过去再说。 */
    private fun findNearestOre(maid: SmartMaidEntity): BlockPos? =
        scanArea(maid)
            .filter { !this.skipped.contains(it) }
            .minByOrNull { it.distSqr(maid.blockPosition()) }

    /** 区域扫描：返回区域内所有矿石位置。水平 range、垂直 Y_RANGE（比水平小）。 */
    private fun scanArea(maid: SmartMaidEntity): List<BlockPos> {
        val center = this.center ?: return emptyList()
        val a = center.offset(-this.range, -Y_RANGE, -this.range)
        val b = center.offset(this.range, Y_RANGE, this.range)
        val ores = ArrayList<BlockPos>()
        for (pos in BlockPos.betweenClosed(a, b)) {
            val state = maid.level().getBlockState(pos)
            if (!state.isAir && isOre(state)) {
                ores.add(pos.immutable())
            }
        }
        return ores
    }

    companion object {
        /** 垂直扫描范围（格）：比水平小，避免 y 方向扫太深/太高 */
        private const val Y_RANGE = 8

        /** 判定方块是否为矿石（命中任一矿石标签）。实现已收拢到感知工具 PerceptionBlockUtil。 */
        @JvmStatic
        fun isOre(state: BlockState): Boolean = PerceptionBlockUtil.isOre(state)
    }
}
