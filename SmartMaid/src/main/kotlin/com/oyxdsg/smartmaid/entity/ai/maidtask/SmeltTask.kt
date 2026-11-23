package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.compat.MaidCompat
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.RecipeManager
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.SingleRecipeInput
import net.minecraft.world.item.crafting.SmeltingRecipe
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import java.util.UUID

/**
 * 熔炉烧炼任务（P2）：在附近找空闲熔炉 → 放能烧成目标的原矿 + 燃料 → 轮询成品 → 进背包，
 * count 达成或材料/熔炉缺失结束。
 *
 * 熔炉租约（§8.8b）：一个熔炉同一时间只被一个 smelt 任务占用。无空闲熔炉时本任务
 * 上报 isBlocked() → 调度器暂停（QueuedTask.PauseReason.BLOCKED）并让出队头，
 * 熔炉空出后再恢复；被暂停（战斗/插队）时释放租约，恢复时重新 acquire。
 */
open class SmeltTask(targetParam: ItemStack, countParam: Int) : MaidAITask("smelt"), Resumable {

    private val target: ItemStack = targetParam.copy()
    private val count: Int = maxOf(1, countParam)
    private val ownerId: String = "smelt-" + UUID.randomUUID()
    private var completed: Int = 0
    private var furnacePos: BlockPos? = null
    private var done: Boolean = false
    private var missing: Boolean = false
    /** 有熔炉但无空闲 → 阻塞让出队头（§8.8b）。 */
    private var blocked: Boolean = false
    /** 下次重扫熔炉的 tick（阻塞退避，避免每 tick 全量扫描）。 */
    private var nextScanTick: Int = 0
    private var waitTicks: Int = 0

    override fun canStart(maid: SmartMaidEntity): Boolean = !this.target.isEmpty

    override fun start(maid: SmartMaidEntity) {
        FurnaceLease.releaseAll(this.ownerId)
        this.completed = 0
        this.furnacePos = null
        this.done = false
        this.missing = false
        this.blocked = false
        this.nextScanTick = 0
        MaidDebug.log("Smelt start 目标=" + this.target.item.descriptionId + " x" + this.count)
    }

    override fun tick(maid: SmartMaidEntity) {
        if (this.completed >= this.count) {
            return
        }
        // 1. 定位空闲熔炉（附近 10 格内最近的未被占用熔炉）
        if (this.furnacePos == null) {
            this.blocked = false
            if (maid.tickCount < this.nextScanTick) {
                this.blocked = true // 退避中：继续让出队头，不重复扫描
                return
            }
            val range = 10
            this.furnacePos = FurnaceLease.findFree(maid.level(), maid.blockPosition(), range, this.ownerId)
            if (this.furnacePos == null) {
                if (FurnaceLease.anyFurnace(maid.level(), maid.blockPosition(), range)) {
                    this.blocked = true // 有熔炉但都被占用
                    this.nextScanTick = maid.tickCount + 40
                    MaidDebug.log("Smelt 无空闲熔炉，让出队头")
                } else {
                    this.missing = true // 附近根本没有熔炉
                    maid.showBubble(Component.literal("附近没有熔炉"), 60)
                }
                return
            }
            FurnaceLease.acquire(this.furnacePos, this.ownerId)
        }
        // 2. 走到熔炉旁
        val pos = this.furnacePos ?: return
        if (!MaidActions.isWithinReach(maid, pos, 3.0)) {
            if (maid.getNavigation().isDone || maid.tickCount % 20 == 0) {
                MaidActions.navigateTo(maid, pos, 1.0)
            }
            return
        }
        maid.getNavigation().stop()

        val be = maid.level().getBlockEntity(pos)
        if (be !is AbstractFurnaceBlockEntity) {
            FurnaceLease.release(pos, this.ownerId)
            this.furnacePos = null
            return
        }

        // 3. 轮询成品槽（2）
        val result = be.getItem(2)
        if (!result.isEmpty) {
            val taken = result.copy()
            be.setItem(2, ItemStack.EMPTY)
            val left = MaidActions.storeToBackpack(maid, taken)
            if (!left.isEmpty) {
                Block.popResource(maid.level(), pos, left) // 背包满则掉在熔炉旁
            }
            this.completed += taken.count
            MaidDebug.log("Smelt 取出成品 x" + taken.count + " 累计=" + this.completed)
            return
        }

        // 4. 补料：input(0) 空 → 放能烧成 target 的原矿
        if (be.getItem(0).isEmpty) {
            val input = findSmeltableInput(maid)
            if (input.isEmpty) {
                this.missing = true
                maid.showBubble(
                    Component.literal("背包里没有可烧的 " + this.target.item.descriptionId),
                    60
                )
                return
            }
            be.setItem(0, input.copy())
            input.shrink(1)
            maid.getMaidInventory().setChanged()
        }
        // fuel(1) 空 → 放燃料
        if (be.getItem(1).isEmpty) {
            val fuel = findFuel(maid)
            if (fuel.isEmpty) {
                this.missing = true
                maid.showBubble(Component.literal("背包里没有燃料"), 60)
                return
            }
            be.setItem(1, fuel.copy())
            fuel.shrink(1)
            maid.getMaidInventory().setChanged()
        }
        // 5. 等待烧炼（熔炉在区块加载时自动 tick）
        this.waitTicks++
        if (MaidDebug.verbose() && this.waitTicks % 40 == 0) {
            MaidDebug.log("Smelt 等待烧炼中 " + this.waitTicks + " tick")
        }
    }

    override fun isDone(): Boolean = this.completed >= this.count || this.missing

    override fun isBlocked(): Boolean = this.blocked

    override fun onSuspend() {
        // 暂停（战斗/插队/让出队头）→ 释放租约，恢复时重新 acquire
        FurnaceLease.release(this.furnacePos, this.ownerId)
        this.furnacePos = null
    }

    override fun forceStop(maid: SmartMaidEntity?) {
        FurnaceLease.releaseAll(this.ownerId)
        this.furnacePos = null
    }

    override fun result(): String = if (this.missing)
        "缺材料/熔炉，已烧 " + this.completed + " 个"
    else
        "已烧 " + this.completed + " 个 " + this.target.item.descriptionId

    // ---------- Resumable（Q7） ----------

    override fun saveState(): JsonObject {
        val o = JsonObject()
        o.addProperty("version", stateVersion())
        o.addProperty("completed", this.completed)
        o.addProperty("missing", this.missing)
        this.furnacePos?.let { o.add("furnacePos", TaskState.pos(it)) }
        return o
    }

    override fun restoreState(state: JsonObject?) {
        if (state == null) return
        this.completed = if (state.has("completed")) state.get("completed").asInt else 0
        this.missing = state.has("missing") && state.get("missing").asBoolean
        // 熔炉位置不回溯（熔炉可能已被占用/移除）→ 恢复时重新 acquire（§8.8b）
        this.furnacePos = null
        this.blocked = false
        this.nextScanTick = 0
    }

    override fun stateVersion(): Int = 1

    override fun validateState(maid: SmartMaidEntity?, state: JsonObject?): Boolean =
        !this.target.isEmpty && maid != null

    /** 从背包找能烧成 target 的原矿 */
    private fun findSmeltableInput(maid: SmartMaidEntity): ItemStack {
        val level = maid.level() as? ServerLevel ?: return ItemStack.EMPTY
        val rm: RecipeManager = level.recipeAccess()
        val inv: SimpleContainer = maid.getMaidInventory()
        for (s in 0 until inv.containerSize) {
            val stack = inv.getItem(s)
            if (stack.isEmpty) {
                continue
            }
            val input = SingleRecipeInput(stack)
            val holder: RecipeHolder<SmeltingRecipe>? =
                rm.getRecipeFor(RecipeType.SMELTING, input, level).orElse(null)
            if (holder != null) {
                val out = holder.value().assemble(input)
                if (!out.isEmpty && ItemStack.isSameItemSameComponents(out, this.target)) {
                    return stack
                }
            }
        }
        return ItemStack.EMPTY
    }

    /** 从背包找燃料 */
    private fun findFuel(maid: SmartMaidEntity): ItemStack {
        val level = maid.level() as? ServerLevel ?: return ItemStack.EMPTY
        val inv: SimpleContainer = maid.getMaidInventory()
        for (s in 0 until inv.containerSize) {
            val stack = inv.getItem(s)
            // 「怎么判燃料」是版本敏感的：26.2 用 Level#fuelValues()，26.3 删掉它改用物品组件
            // → 走版本差异隔离层
            if (MaidCompat.isFuel(level, stack)) {
                return stack
            }
        }
        return ItemStack.EMPTY
    }
}
