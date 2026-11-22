package com.oyxdsg.smartmaid.entity.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.data.SmartMaidConfig
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import java.util.WeakHashMap
import kotlin.math.min

/**
 * 女仆背包整理：背包塞不下时，合并同类堆叠 + 丢弃垃圾物品。
 *
 * Java 侧公开 API（@JvmRecord Result + 静态 isJunk/isStorageFull/tidyIfStuck/tidy/dropBehind）
 * 保持不变；MaidAutoTest.java 以 `MaidInventoryTidy.Result r` + r.merged()/r.dropped() 读取。
 */
object MaidInventoryTidy {

    /** 垃圾白名单 tag：数据包可覆盖（data/smartmaid/tags/item/junk.json）。 */
    private val JUNK: TagKey<Item> =
        TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("smartmaid", "junk"))

    /** 整理结果：merged=合并件数，dropped=丢弃件数。 */
    @JvmRecord
    data class Result(val merged: Int, val dropped: Int) {
        fun freed(): Boolean = this.merged > 0 || this.dropped > 0
    }

    /** 是否为垃圾物品（数据包 tag 判定）。 */
    @JvmStatic
    fun isJunk(stack: ItemStack): Boolean = !stack.isEmpty && stack.`is`(JUNK)

    /** 背包区（0-35）是否已无空槽。 */
    @JvmStatic
    fun isStorageFull(maid: SmartMaidEntity): Boolean {
        val inv = maid.getMaidInventory()
        for (i in 0 until STORAGE_SLOTS) {
            if (inv.getItem(i).isEmpty) return false
        }
        return true
    }

    /**
     * 背包塞不下时调用：若已满则整理一次（合并同类 + 丢垃圾）。
     * @return true 表示这次整理有动作（合并过或丢过），调用方应重试放入
     */
    @JvmStatic
    fun tidyIfStuck(maid: SmartMaidEntity): Boolean {
        if (maid.level().isClientSide || !SmartMaidConfig.autoTidyWhenFull()) return false
        if (!isStorageFull(maid)) return false
        val r = tidy(maid)
        if (r.freed()) {
            MaidDebug.log("背包整理: 合并 " + r.merged + " 件 / 丢弃垃圾 " + r.dropped + " 件")
            return true
        }
        // 满了、又没有垃圾可丢 → 上报给 AI（带节流，避免每 tick 刷屏）
        reportFull(maid)
        return false
    }

    /** 整理：合并同类堆叠 → 丢弃全部垃圾。不检查开关/是否满，供测试与手动调用。 */
    @JvmStatic
    fun tidy(maid: SmartMaidEntity): Result {
        if (maid.level().isClientSide) return Result(0, 0)
        val inv = maid.getMaidInventory()
        val merged = mergeStacks(inv)
        val dropped = dropJunk(maid, inv)
        if (merged > 0 || dropped > 0) inv.setChanged()
        return Result(merged, dropped)
    }

    /** 把后面的同类堆叠合并进前面的槽，腾出空槽；返回合并的物品总数。 */
    private fun mergeStacks(inv: SimpleContainer): Int {
        var merged = 0
        for (i in 0 until STORAGE_SLOTS) {
            val dst = inv.getItem(i)
            if (dst.isEmpty) continue
            val max = dst.maxStackSize
            var j = i + 1
            while (j < STORAGE_SLOTS && dst.count < max) {
                val src = inv.getItem(j)
                if (!src.isEmpty && ItemStack.isSameItemSameComponents(dst, src)) {
                    val move = min(max - dst.count, src.count)
                    dst.grow(move)
                    src.shrink(move)
                    merged += move
                    if (src.isEmpty) inv.setItem(j, ItemStack.EMPTY)
                }
                j++
            }
        }
        return merged
    }

    /** 丢弃全部垃圾，返回丢弃件数。 */
    private fun dropJunk(maid: SmartMaidEntity, inv: SimpleContainer): Int {
        var dropped = 0
        for (i in 0 until STORAGE_SLOTS) {
            val stack = inv.getItem(i)
            if (!isJunk(stack)) continue
            dropBehind(maid, stack.copy())
            dropped += stack.count
            inv.setItem(i, ItemStack.EMPTY)
        }
        return dropped
    }

    /** 把物品丢到女仆身后一格（按当前朝向反方向），并加拾取冷却。 */
    @JvmStatic
    fun dropBehind(maid: SmartMaidEntity, stack: ItemStack) {
        if (stack.isEmpty) return
        val level: Level = maid.level()
        val yaw = Math.toRadians(maid.getYRot().toDouble())
        // 身后 = 前方向 (-sin, cos) 取反
        val x = maid.getX() + Math.sin(yaw)
        val z = maid.getZ() - Math.cos(yaw)
        val item = ItemEntity(level, x, maid.getY() + 0.25, z, stack)
        item.setPickUpDelay(40)
        level.addFreshEntity(item)
        maid.suppressPickup(200)
        if (MaidDebug.verbose()) {
            MaidDebug.log("丢弃垃圾 " + MaidDebug.itemName(stack) + " x" + stack.count + " -> 身后")
        }
    }

    /** 上报"背包已满且无垃圾可丢"给 AI（经感知事件通道 → 文件 + WebSocket），带节流。 */
    private fun reportFull(maid: SmartMaidEntity) {
        val now = maid.tickCount
        val last = LAST_REPORT[maid]
        if (last != null && now - last < REPORT_INTERVAL) return
        LAST_REPORT[maid] = now

        val payload = JsonObject()
        payload.addProperty("reason", "no_junk")
        payload.addProperty("usedSlots", STORAGE_SLOTS)
        payload.addProperty("freeSlots", 0)
        val items = JsonArray()
        val inv = maid.getMaidInventory()
        for (i in 0 until STORAGE_SLOTS) {
            val stack = inv.getItem(i)
            if (stack.isEmpty) continue
            val entry = JsonObject()
            entry.addProperty("slot", i)
            entry.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
            entry.addProperty("count", stack.count)
            items.add(entry)
        }
        payload.add("inventory", items)
        maid.getPerceptionModule().reportEvent("inventory_full", payload)
        MaidDebug.log("背包已满且无垃圾可丢，已上报 AI（36/36）")
    }

    private const val STORAGE_SLOTS = 36
    private const val REPORT_INTERVAL = 600
    private val LAST_REPORT = WeakHashMap<SmartMaidEntity, Int>()
}
