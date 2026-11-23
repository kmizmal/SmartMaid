package com.oyxdsg.smartmaid.entity.ai.structure

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.function.Predicate

/**
 * 结构扫描（Atomic Command Protocol §5.2）：find 算法的实现。
 *
 * 以女仆为中心，从 range 起逐轮扩大（expand）直到 max_range：
 * 收集 core 方块 → BFS 连通组件（core ∪ support）→ 结构判定 → 产物过滤（produce 谓词）
 * → 物种对齐（produce 指定 wood 家族时要求树叶同物种）→ 返回第一个命中组件。
 * 性能由 scanBlockLimit 与 max_range 双重护栏。
 */
object StructureScan {

    /** find 默认参数（Atomic Command Protocol §九 护栏） */
    const val DEFAULT_RANGE = 12
    const val DEFAULT_MAX_RANGE = 32
    const val DEFAULT_EXPAND = 4

    /** 垂直扫描范围（格）：树通常向上生长 */
    private const val Y_DOWN = 6
    private const val Y_UP = 24

    /** find 查询（查询原子，无副作用） */
    @JvmStatic
    fun find(maid: SmartMaidEntity, params: JsonObject): JsonObject {
        val structureId = str(params, "structure", "tree")
        val st = StructureRegistry.get(structureId)
            ?: return fail("未知结构类型: " + structureId)
        val produce = str(params, "produce", null)
        val filter = ProduceFilter.compile(produce)
        val matcher: Predicate<BlockState> = filter.matcher ?: Predicate<BlockState> { true }
        val range = maxOf(1, intParam(params, "range", DEFAULT_RANGE))
        val maxRange = maxOf(range, intParam(params, "max_range", DEFAULT_MAX_RANGE))
        val expand = maxOf(1, intParam(params, "expand", DEFAULT_EXPAND))

        val level: Level = maid.level()
        val center = maid.blockPosition()
        val limit = st.scanBlockLimit()
        var scanned = 0
        // 退化候选：最近的"结构合法、但产物不匹配"的组件。
        // 若整轮扫描都找不到产物匹配的结构，就返回它并标注 produce_match=false ——
        // 深色橡木也是树，不能因为产物谓词写着 oaks 就让女仆"看不见树"。
        var fallback: JsonObject? = null

        var r = range
        while (r <= maxRange) {
            if (MaidDebug.verbose()) {
                MaidDebug.log(
                    "Script find: structure=" + structureId + " produce=" + produce
                            + " 范围 " + r + "/" + maxRange + " 已扫 " + scanned
                )
            }
            val a = center.offset(-r, -Y_DOWN, -r)
            val b = center.offset(r, Y_UP, r)
            val seen = HashSet<Long>()
            for (pos in BlockPos.betweenClosed(a, b)) {
                if (++scanned > limit) {
                    break
                }
                val s = level.getBlockState(pos)
                if (!st.coreBlock().test(s) || seen.contains(pos.asLong())) {
                    continue
                }
                val comp = collectComponent(level, pos, st, seen, st.scanBlockLimit())
                    ?: continue
                if (!st.isStructure(comp)) {
                    continue
                }
                if (!matchesProduce(level, comp, matcher)) {
                    if (MaidDebug.verbose()) {
                        MaidDebug.log(
                            "Script find 过滤: structure=" + structureId + " produce=" + produce
                                    + " 组件 core=" + comp.coreCount() + " 未命中产物"
                        )
                    }
                    if (fallback == null) {
                        val fb = buildResult(maid, level, st, comp, matcher)
                        fb.addProperty("produce_match", false)
                        fb.addProperty("note", "附近没有匹配产物的结构，返回最近的同类结构")
                        fallback = fb
                    }
                    continue
                }
                val species = filter.species
                if (species != null && !hasSpeciesLeaves(level, comp, species)) {
                    if (MaidDebug.verbose()) {
                        MaidDebug.log(
                            "Script find 物种对齐失败: structure=" + structureId
                                    + " produce=" + produce + " 组件无该物种树叶"
                        )
                    }
                    continue
                }
                val hit = buildResult(maid, level, st, comp, matcher)
                hit.addProperty("produce_match", true)
                MaidDebug.log(
                    "Script find 命中: " + structureId + " pos=" + hit.get("pos")
                            + " remaining=" + comp.coreCount() + " matches=" + hit.get("produce_matches")
                )
                return hit
            }
            if (scanned > limit) {
                break
            }
            r += expand
        }
        val fb = fallback
        if (fb != null) {
            MaidDebug.log(
                "Script find 退化命中(附近无匹配产物的结构): " + structureId
                        + " pos=" + fb.get("pos") + " matches=" + fb.get("produce_matches")
            )
            return fb
        }
        MaidDebug.log(
            "Script find 未命中: structure=" + structureId + " produce=" + produce
                    + " searched=" + maxRange + " scanned=" + scanned
        )
        val out = JsonObject()
        out.addProperty("found", false)
        out.addProperty("structure", structureId)
        out.addProperty("searched", maxRange)
        return out
    }

    /** 沿 core ∪ support 做 6 邻域 BFS 连通组件；超限返回 null（放弃该组件） */
    private fun collectComponent(
        level: Level,
        seed: BlockPos,
        st: StructureType,
        seen: MutableSet<Long>,
        limit: Int
    ): StructureComponent? {
        val queue = ArrayDeque<BlockPos>()
        val cores = ArrayList<BlockPos>()
        val supports = ArrayList<BlockPos>()
        queue.add(seed)
        seen.add(seed.asLong())
        var n = 0
        while (!queue.isEmpty()) {
            if (n >= limit) {
                return null
            }
            val p = queue.poll()
            n++
            val s = level.getBlockState(p)
            if (st.coreBlock().test(s)) {
                cores.add(p)
            } else if (st.supportBlock().test(s)) {
                supports.add(p)
            }
            for (d in Direction.entries) {
                val q = p.relative(d)
                if (!seen.add(q.asLong())) {
                    continue
                }
                val qs = level.getBlockState(q)
                if (st.coreBlock().test(qs) || st.supportBlock().test(qs)) {
                    queue.add(q)
                }
            }
        }
        return StructureComponent(cores, supports)
    }

    /** 组件内是否存在命中 produce 谓词的 core */
    private fun matchesProduce(
        level: Level,
        comp: StructureComponent,
        matcher: Predicate<BlockState>
    ): Boolean {
        for (p in comp.cores()) {
            if (matcher.test(level.getBlockState(p))) {
                return true
            }
        }
        return false
    }

    /** 物种对齐：组件内 support（树叶）包含该物种对应树叶 */
    private fun hasSpeciesLeaves(level: Level, comp: StructureComponent, species: String): Boolean {
        val leavesId: Identifier = TreeStructure.leavesIdOf(species)
            ?: return true // 该物种无树叶概念 → 不额外要求
        for (p in comp.supports()) {
            val bid: Identifier = BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).block)
            if (bid != null && bid == leavesId) {
                return true
            }
        }
        return false
    }

    /**
     * 命中结果：pos=离女仆最近的 core（core = 资源本身，如 #logs 含所有木头）；
     * produce_matches=组件内命中产物谓词的方块类型去重；remaining=组件 core 数。
     *
     * pos 为什么用 core 而不是"命中产物的 core"：结构层的判据是资源本身——structure=tree
     * 时"树"指相连的原木，深色橡木、白桦、橡木同样是树。produce 只用于挑哪一棵树，
     * 不该反过来定义"什么算树"，否则附近有棵深色橡木时会退化成"一个目标都没有"。
     */
    private fun buildResult(
        maid: SmartMaidEntity,
        level: Level,
        st: StructureType,
        comp: StructureComponent,
        matcher: Predicate<BlockState>
    ): JsonObject {
        var nearest: BlockPos? = null
        var best = Double.MAX_VALUE
        val maidPos = maid.blockPosition()
        for (p in comp.cores()) {
            val d = p.distSqr(maidPos)
            if (d < best) {
                best = d
                nearest = p
            }
        }
        val out = JsonObject()
        out.addProperty("found", true)
        out.addProperty("structure", st.id())
        if (nearest != null) {
            out.add("pos", posArray(nearest))
        }
        val types = LinkedHashSet<String>()
        for (p in comp.cores()) {
            val s = level.getBlockState(p)
            if (matcher.test(s)) {
                val bid: Identifier = BuiltInRegistries.BLOCK.getKey(s.block)
                if (bid != null) {
                    types.add(bid.toString())
                }
            }
        }
        val matches = JsonArray()
        for (t in types) {
            matches.add(t)
        }
        out.add("produce_matches", matches)
        out.addProperty("remaining", comp.coreCount())
        return out
    }

    @JvmStatic
    fun posArray(pos: BlockPos): JsonArray {
        val arr = JsonArray()
        arr.add(pos.x)
        arr.add(pos.y)
        arr.add(pos.z)
        return arr
    }

    private fun str(o: JsonObject, key: String, def: String?): String? {
        return if (o.has(key) && o.get(key).isJsonPrimitive) o.get(key).asString else def
    }

    private fun intParam(o: JsonObject, key: String, def: Int): Int {
        return if (o.has(key) && o.get(key).isJsonPrimitive && o.get(key).asJsonPrimitive.isNumber
        ) {
            o.get(key).asInt
        } else {
            def
        }
    }

    private fun fail(reason: String): JsonObject {
        val out = JsonObject()
        out.addProperty("found", false)
        out.addProperty("error", reason)
        return out
    }
}
