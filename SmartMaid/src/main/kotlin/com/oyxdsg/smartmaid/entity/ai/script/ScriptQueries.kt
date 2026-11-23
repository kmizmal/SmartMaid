package com.oyxdsg.smartmaid.entity.ai.script

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidAIBridge
import com.oyxdsg.smartmaid.entity.ai.structure.StructureScan
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import java.util.function.Predicate

/**
 * 查询原子（Atomic Command Protocol §四 B）：inventory / block_at / find / find_entity /
 * find_item / distance。全部**无副作用**，返回结构化 result 供变量绑定与条件判断。
 */
object ScriptQueries {

    /** 本层可识别的查询指令 */
    @JvmStatic
    fun isQuery(cmd: String): Boolean = when (cmd) {
        "inventory", "block_at", "find", "find_entity", "find_item", "distance" -> true
        else -> false
    }

    /** 执行查询；失败时 err 非空且返回 null */
    @JvmStatic
    fun query(maid: SmartMaidEntity, cmd: String, params: JsonObject, err: StringBuilder): JsonObject? =
        when (cmd) {
            "inventory" -> inventory(maid, params)
            "block_at" -> blockAt(maid, params, err)
            "find" -> StructureScan.find(maid, params)
            "find_entity" -> findEntity(maid, params)
            "find_item" -> findItem(maid, params)
            "distance" -> distance(maid, params, err)
            else -> {
                err.append("未知查询: ").append(cmd)
                null
            }
        }

    // ---------- 背包 ----------

    /** inventory：item（id / #tag / 缺省=全列）→ {has, count, slot, items[]} */
    private fun inventory(maid: SmartMaidEntity, params: JsonObject): JsonObject {
        val itemSpec = if (params.has("item")) params.get("item").asString else null
        val filter = itemFilter(itemSpec)
        val inv: SimpleContainer = maid.maidInventory
        var total = 0
        var firstSlot = -1
        val items = JsonArray()
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            if (stack.isEmpty || !filter.test(stack)) continue
            val count = stack.count
            total += count
            if (firstSlot < 0) firstSlot = i
            if (itemSpec == null || itemSpec.startsWith("#")) {
                val s = JsonObject()
                s.addProperty("slot", i)
                s.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.item).toString())
                s.addProperty("count", count)
                items.add(s)
            }
        }
        val out = JsonObject()
        out.addProperty("has", total > 0)
        out.addProperty("count", total)
        out.addProperty("slot", firstSlot)
        out.add("items", items)
        return out
    }

    /** item 参数 → 谓词：null/#tag → 全匹配；#tag → tag 匹配；id → 精确匹配 */
    @JvmStatic
    fun itemFilter(itemSpec: String?): Predicate<ItemStack> {
        if (itemSpec == null || itemSpec.isEmpty()) {
            return Predicate { s: ItemStack -> !s.isEmpty }
        }
        if (itemSpec.startsWith("#")) {
            val id = Identifier.tryParse(itemSpec.substring(1)) ?: return Predicate { false }
            val tag: TagKey<Item> = TagKey.create(Registries.ITEM, id)
            return Predicate { s: ItemStack -> !s.isEmpty && s.`is`(tag) }
        }
        val id = Identifier.tryParse(itemSpec) ?: return Predicate { false }
        val item: Item? = BuiltInRegistries.ITEM.get(id)
            .map { it.value() }
            .orElse(null)
        return Predicate { s: ItemStack -> !s.isEmpty && item != null && s.`is`(item) }
    }

    // ---------- 方块 ----------

    /** block_at：pos → {block, hardness, breakable, tool_required} */
    private fun blockAt(maid: SmartMaidEntity, params: JsonObject, err: StringBuilder): JsonObject? {
        val pos = pos(maid, params, err) ?: return null
        val level: Level = maid.level()
        val state: BlockState = level.getBlockState(pos)
        val hardness = state.getDestroySpeed(level, pos)
        val isAir = state.isAir
        val out = JsonObject()
        out.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.block).toString())
        out.addProperty("hardness", hardness)
        out.addProperty("breakable", !isAir && hardness >= 0.0f)
        out.addProperty("tool_required", !isAir && state.requiresCorrectToolForDrops())
        return out
    }

    // ---------- 结构 / 实体 / 距离 ----------

    /** find_entity：type? / range → {found, uuid, type, pos, dist} */
    private fun findEntity(maid: SmartMaidEntity, params: JsonObject): JsonObject {
        val type: EntityType<*>? = if (params.has("type")) {
            Identifier.tryParse(params.get("type").asString)?.let { id ->
                BuiltInRegistries.ENTITY_TYPE.get(id).map { it.value() }.orElse(null)
            }
        } else {
            null
        }
        val range = intParam(params, "range", 16)
        val nearest: LivingEntity? = MaidActions.findEntities(
            maid,
            LivingEntity::class.java,
            range.toDouble()
        ) { e: LivingEntity ->
            e.isAlive && e !== maid && e !== maid.getOwner() && (type == null || e.type === type)
        }.stream()
            .min(Comparator.comparingDouble { e: LivingEntity -> maid.distanceToSqr(e) })
            .orElse(null)

        val out = JsonObject()
        if (nearest == null) {
            out.addProperty("found", false)
            return out
        }
        out.addProperty("found", true)
        out.addProperty("uuid", nearest.uuid.toString())
        out.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(nearest.type).toString())
        out.add("pos", StructureScan.posArray(nearest.blockPosition()))
        out.addProperty("dist", Math.round(maid.distanceTo(nearest) * 10.0) / 10.0)
        return out
    }

    /** find_item：item? / range → {found, pos, count} */
    private fun findItem(maid: SmartMaidEntity, params: JsonObject): JsonObject {
        val itemSpec = if (params.has("item")) params.get("item").asString else null
        val range = intParam(params, "range", 8)
        val item: ItemEntity? = MaidActions.findNearestItem(maid, range.toDouble(), itemFilter(itemSpec))
        val out = JsonObject()
        if (item == null) {
            out.addProperty("found", false)
            return out
        }
        out.addProperty("found", true)
        out.add("pos", StructureScan.posArray(item.blockPosition()))
        out.addProperty("count", item.item.count)
        return out
    }

    /** distance：pos → {dist}（水平距离） */
    private fun distance(maid: SmartMaidEntity, params: JsonObject, err: StringBuilder): JsonObject? {
        val pos = pos(maid, params, err) ?: return null
        val dx = maid.x - (pos.x + 0.5)
        val dz = maid.z - (pos.z + 0.5)
        val out = JsonObject()
        out.addProperty("dist", Math.round(Math.sqrt(dx * dx + dz * dz) * 10.0) / 10.0)
        return out
    }

    // ---------- 工具 ----------

    private fun pos(maid: SmartMaidEntity, params: JsonObject, err: StringBuilder): BlockPos? =
        MaidAIBridge.parsePos(maid, params, "pos", err)

    private fun intParam(o: JsonObject, key: String, def: Int): Int =
        if (o.has(key) && o.get(key).isJsonPrimitive && o.get(key).asJsonPrimitive.isNumber) {
            o.get(key).asInt
        } else {
            def
        }
}
