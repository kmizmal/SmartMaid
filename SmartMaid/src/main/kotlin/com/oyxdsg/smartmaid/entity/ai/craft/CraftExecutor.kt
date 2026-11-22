package com.oyxdsg.smartmaid.entity.ai.craft

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.compat.MaidCompat
import com.oyxdsg.smartmaid.data.SmartMaidConfig
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import it.unimi.dsi.fastutil.ints.IntList
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.PlacementInfo
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.RecipeManager
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.ShapedRecipe
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 制作执行器（M3）：女仆消耗背包材料直接合成目标物品（不需要工作台）。
 *
 * 核心流程（AI 只说成品，女仆自动找配方凑材料）：
 * 1. 按成品过滤候选配方（优先用物品自带 [DataComponents.RECIPES] 配方 id 列表，
 *    兜底遍历 RecipeManager 全部 crafting 配方）
 * 2. 对每个候选：用 [PlacementInfo.slotsToIngredientIndex] 的 row-major 布局
 *    （按配方真实宽高），从背包抽材料构建 [CraftingInput] 并 `matches` 验证
 * 3. 匹配成功且 `assemble` 产物 = 目标物品 → 消耗背包材料 → 产物进背包
 *
 * 布局按配方真实尺寸（ShapedRecipe.getWidth/getHeight）构建 CraftingInput，
 * 因此 1x1（原木→木板）、1x2（木板→木棍）、2x2（工作台）、3x3（铁镐）都能正确匹配；
 * 不需要工作台方块。
 */
class CraftExecutor private constructor() {

    /** 一次合成的构建结果：CraftingInput + 每背包槽消耗量 + 产物 */
    private class CraftResult(val input: CraftingInput, val consume: IntArray, val out: ItemStack)

    /** 配方材料需求：去重后的 ingredient 列表 + 每种在 3x3 布局中出现次数。 */
    private class IngredientNeeds(val ingredients: List<Ingredient>, val need: IntArray)

    companion object {

        /** 可合成产物白名单（`craftWhitelistMode=tag` 时生效）：data/smartmaid/tags/item/craftable.json */
        private val CRAFTABLE: TagKey<Item> =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("smartmaid", "craftable"))

        /**
         * 全量 crafting 配方缓存（2026-09-29，整合包兼容）。
         *
         * 只用于兜底路径（目标物品没有自带 [DataComponents.RECIPES] 时）。整合包配方可达
         * 数千条，原先每次合成/预检都要全表扫描；现在只扫一次并缓存。
         *
         * 失效时机：服务器启动时调 [invalidateCache]。
         * 注意 `/reload` 后新增的配方在本次运行期间不会被缓存看到 —— 可接受的取舍，
         * 女仆按服务器启动时的配方集工作。
         */
        @Volatile
        private var allCraftingCache: List<RecipeHolder<CraftingRecipe>>? = null

        /**
         * 递归合成深度上限（原木→木板→木棍→目标 = 2 层中间物，留裕量）。
         * 超过上限放弃，防止配方链无限展开。
         */
        private const val MAX_RECURSE_DEPTH = 4

        /** 递归合成中间物时单次 while 的最大安全次数（防极端情况死循环） */
        private const val MAX_CRAFT_GUARD = 256

        /** ingredient 候选项最多回报几个（tag 类 ingredient 可能展开成上百项） */
        private const val MAX_OPTIONS = 16

        /** 清空配方缓存（服务器启动后调用） */
        @JvmStatic
        fun invalidateCache() {
            allCraftingCache = null
            MaidDebug.log("CraftExecutor 配方缓存已清空")
        }

        /** 全量 crafting 配方（带缓存；仅在物品没带 RECIPES 组件时使用） */
        @Suppress("UNCHECKED_CAST")
        private fun allCraftingRecipes(rm: RecipeManager): List<RecipeHolder<CraftingRecipe>> {
            val cache = allCraftingCache
            if (cache == null) {
                val list = ArrayList<RecipeHolder<CraftingRecipe>>()
                for (holder in rm.getRecipes()) {
                    if (holder.value().getType() == RecipeType.CRAFTING
                        && holder.value() is CraftingRecipe
                    ) {
                        list.add(holder as RecipeHolder<CraftingRecipe>)
                    }
                }
                val newCache = list.toList()
                allCraftingCache = newCache
                return newCache
            }
            return cache
        }

        /**
         * 合成白名单判定：整合包作者可用它限制"女仆能凭空合成什么"，
         * 避免绕过整合包刻意设计的进度门槛。
         *
         * `craftWhitelistMode`（config/smartmaid/main.json）：
         * - `off`（默认）—— 不限制，保持既有能力；
         * - `vanilla-only` —— 只允许原版（`minecraft:`）命名空间的配方；
         * - `tag` —— 只允许产物在 `#smartmaid:craftable` tag 内的配方。
         */
        private fun craftAllowed(holder: RecipeHolder<CraftingRecipe>, target: ItemStack): Boolean {
            return when (SmartMaidConfig.craftWhitelistMode()) {
                "vanilla-only" -> "minecraft" == holder.id().identifier().getNamespace()
                "tag" -> target.`is`(CRAFTABLE)
                else -> true
            }
        }

        /**
         * 尝试合成目标物品（支持递归：背包里没有的直接材料，可由其他配方逐层合成补足）。
         *
         * 例如背包没有木棍但有原木时，会自动先合成木板、木棍，再合成目标。
         *
         * **循环防护**：递归带「展开链 visited + 深度上限」，诸如「铁块→铁锭→铁块」
         * 这类互成配方的循环不会无限展开（展开链上出现过的物品直接放弃该分支）。
         *
         * @return 成功返回产物 ItemStack（已进背包）；失败返回 EMPTY
         */
        @JvmStatic
        fun craft(maid: SmartMaidEntity, target: ItemStack): ItemStack {
            if (maid.level().isClientSide || maid.level() !is ServerLevel) {
                return ItemStack.EMPTY
            }
            if (!ensureMaterials(maid, target, 1, 0, HashSet<Item>())) {
                MaidDebug.log("craft 缺材料(含递归合成): " + target.getItem().getDescriptionId())
                return ItemStack.EMPTY
            }
            return craftDirect(maid, target)
        }

        /** 单层合成（材料须已齐备，不递归）。 */
        private fun craftDirect(maid: SmartMaidEntity, target: ItemStack): ItemStack {
            if (maid.level().isClientSide) {
                return ItemStack.EMPTY
            }
            val level = maid.level() as? ServerLevel ?: return ItemStack.EMPTY
            val rm = level.recipeAccess()

            // 1. 候选配方（优先 RECIPES component，兜底全量遍历）
            val candidates = collectCandidates(maid, rm, target)
            if (candidates.isEmpty()) {
                MaidDebug.log("craft 无候选配方: " + target.getItem().getDescriptionId())
                return ItemStack.EMPTY
            }

            // 2. 逐个用背包材料构建并匹配
            for (holder in candidates) {
                val result = buildInput(maid, holder.value()) ?: continue // 材料不足，试下一个配方
                if (!ItemStack.isSameItemSameComponents(result.out, target)) {
                    continue // 产物不匹配目标，跳过
                }
                // 3. 消耗材料 + 产出进背包
                val inv = maid.getMaidInventory()
                val consume = result.consume
                for (s in consume.indices) {
                    if (consume[s] > 0 && !inv.getItem(s).isEmpty) {
                        inv.getItem(s).shrink(consume[s])
                    }
                }
                inv.setChanged()
                MaidCompat.swing(maid, InteractionHand.MAIN_HAND)
                val left = MaidActions.storeToBackpack(maid, result.out)
                if (!left.isEmpty) {
                    maid.spawnAtLocation(level, left) // 背包满则掉地上
                }
                MaidDebug.log(
                    "craft 成功: " + result.out.getItem().getDescriptionId()
                        + " x" + result.out.getCount()
                )
                return result.out
            }
            MaidDebug.log("craft 缺材料: " + target.getItem().getDescriptionId())
            return ItemStack.EMPTY
        }

        /**
         * 确保背包能凑齐「合成 count 个 target」所需的全部材料；缺的直接材料
         * 会递归合成进背包（如合成木板补木板、合成木棍补木棍）。
         *
         * **循环防护**：`visited` 记录当前展开链上的物品，
         * 再次出现（铁块→铁锭→铁块）即视为该分支不可行。
         *
         * @return 材料齐备可执行真合成；无法凑齐返回 false
         */
        private fun ensureMaterials(
            maid: SmartMaidEntity,
            target: ItemStack,
            count: Int,
            depth: Int,
            visited: MutableSet<Item>
        ): Boolean {
            if (depth > MAX_RECURSE_DEPTH) {
                return false
            }
            if (maid.level().isClientSide) {
                return false
            }
            val level = maid.level() as? ServerLevel ?: return false
            val tItem = target.getItem()
            if (visited.contains(tItem)) {
                return false // 配方循环（如铁块→铁锭→铁块）
            }
            val candidates = collectCandidates(maid, level.recipeAccess(), target)
            if (candidates.isEmpty()) {
                return false
            }
            val next = HashSet(visited)
            next.add(tItem)
            for (holder in candidates) {
                val recipe = holder.value()
                // 全量扫描路径必须验证产物 = target，否则会选中「材料可满足但产出不对」的配方
                if (!produces(maid, recipe, target)) {
                    continue
                }
                val perCraft = outputCount(recipe)
                val crafts = ceil(count.toDouble() / max(1, perCraft)).toInt()
                val inNeeds = ingredientNeeds(recipe)
                val need = inNeeds.need
                val ingredients = inNeeds.ingredients
                var ok = true
                for (idx in need.indices) {
                    if (need[idx] <= 0) {
                        continue
                    }
                    val ing = ingredients[idx]
                    if (!ensureIngredient(maid, ing, need[idx] * crafts, depth + 1, next)) {
                        ok = false
                        break
                    }
                }
                if (ok) {
                    return true // 该配方材料已齐，可执行真合成
                }
            }
            return false
        }

        /**
         * 确保背包里匹配 `ing` 的物品总数 ≥ `need`；不足时递归合成
         * 候选项（取第一个可合成的）并放进背包，直到数量达标。
         */
        private fun ensureIngredient(
            maid: SmartMaidEntity,
            ing: Ingredient,
            need: Int,
            depth: Int,
            visited: MutableSet<Item>
        ): Boolean {
            val inv = maid.getMaidInventory()
            if (countMatching(inv, ing) >= need) {
                return true
            }
            if (depth > MAX_RECURSE_DEPTH) {
                return false
            }
            val candidates = ArrayList<Holder<Item>>()
            try {
                ing.items().limit(MAX_OPTIONS.toLong()).forEach { holder: Holder<Item> -> candidates.add(holder) }
            } catch (_: Throwable) {
            }
            for (holder in candidates) {
                val item = holder.value()
                if (visited.contains(item)) {
                    continue
                }
                val missing = need - countMatching(inv, ing)
                if (missing <= 0) {
                    return true
                }
                if (!ensureMaterials(maid, ItemStack(item), missing, depth + 1, visited)) {
                    continue // 这个候选项无法递归合成，试下一个
                }
                // 真正合成候选项直到匹配量够
                var guard = 0
                while (countMatching(inv, ing) < need) {
                    guard++
                    if (guard > MAX_CRAFT_GUARD) {
                        break
                    }
                    val out = craftDirect(maid, ItemStack(item, 1))
                    if (out.isEmpty) {
                        break
                    }
                }
                if (countMatching(inv, ing) >= need) {
                    return true
                }
            }
            return false
        }

        /**
         * 聚合配方每种材料各需要几个。placement 的 `ingredients()` 是「每格展开」列表
         * （铁镐 = [铁锭,铁锭,铁锭,木棍,木棍]），必须按相同材料聚合 → [3, 2]，否则
         * need 会被低估（铁镐 each=1），递归与干跑判断都会失真。
         */
        private fun ingredientNeeds(recipe: CraftingRecipe): IngredientNeeds {
            val expanded = recipe.placementInfo().ingredients()
            val uniq = ArrayList<Ingredient>()
            val counts = ArrayList<Int>()
            for (ing in expanded) {
                if (ing.isEmpty) {
                    continue
                }
                var found = -1
                for (u in uniq.indices) {
                    if (sameIngredient(uniq[u], ing)) {
                        found = u
                        break
                    }
                }
                if (found < 0) {
                    uniq.add(ing)
                    counts.add(1)
                } else {
                    counts[found] = counts[found] + 1
                }
            }
            val need = IntArray(counts.size)
            for (i in need.indices) {
                need[i] = counts[i]
            }
            return IngredientNeeds(uniq, need)
        }

        /** 两个 ingredient 是否等价（候选物品集合相同）。 */
        private fun sameIngredient(a: Ingredient, b: Ingredient): Boolean {
            if (a === b) {
                return true
            }
            return try {
                val sa = HashSet<Item>()
                val sb = HashSet<Item>()
                a.items().forEach { h: Holder<Item> -> sa.add(h.value()) }
                b.items().forEach { h: Holder<Item> -> sb.add(h.value()) }
                sa.size == sb.size && sa == sb
            } catch (_: Throwable) {
                false
            }
        }

        /** 收集候选配方（产物可能是 target 的 crafting 配方） */
        private fun collectCandidates(
            maid: SmartMaidEntity,
            rm: RecipeManager,
            target: ItemStack
        ): List<RecipeHolder<CraftingRecipe>> {
            val candidates = ArrayList<RecipeHolder<CraftingRecipe>>()
            val keys = target.get(DataComponents.RECIPES)
            if (keys != null && !keys.isEmpty()) {
                for (key in keys) {
                    rm.byKey(key).ifPresent { holder ->
                        if (holder.value() is CraftingRecipe) {
                            // byKey 返回 RecipeHolder<?>，强转目标类型（泛型擦除安全）
                            @Suppress("UNCHECKED_CAST")
                            val cr = holder as RecipeHolder<CraftingRecipe>
                            if (craftAllowed(cr, target)) {
                                candidates.add(cr)
                            }
                        }
                    }
                }
                return candidates
            }
            // 兜底：目标物品没带 RECIPES 组件 → 用缓存的全量 crafting 列表（避免每次全表扫描）
            for (holder in allCraftingRecipes(rm)) {
                if (craftAllowed(holder, target)) {
                    candidates.add(holder)
                }
            }
            return candidates
        }

        /**
         * 用背包材料按配方 3x3 布局构建 CraftingInput；材料不足或布局不匹配返回 null。
         * 匹配成功后调用 `assemble` 得到产物（仅用于产物校验，不真正消耗）。
         */
        private fun buildInput(maid: SmartMaidEntity, recipe: CraftingRecipe): CraftResult? {
            val info = recipe.placementInfo()
            val slotToIng = info.slotsToIngredientIndex()
            val ingredients = info.ingredients()
            val inv = maid.getMaidInventory()

            // 配方真实尺寸：ShapedRecipe 有明确宽高（2x2 工作台 / 3x3 铁镐 / 1x2 木棍），
            // ShapelessRecipe 位置无关按 3x3 兜底。slotToIng 是 row-major 的 width*height
            // 布局（空位 -1），必须按真实宽高建 CraftingInput —— MC 26.2 的
            // ShapedRecipePattern.matches 要求 input 尺寸与配方尺寸严格相等，硬编码 3x3
            // 会让 2x2 / 1x2 / 1x1 配方永远 matches 失败（见 DEVELOPMENT_ISSUES「工作台」）。
            val width = recipeWidth(recipe)
            val height = recipeHeight(recipe)
            val grid = Array(width * height) { ItemStack.EMPTY }
            val consume = IntArray(inv.getContainerSize())

            val size = min(slotToIng.size, width * height)
            for (i in 0 until size) {
                val ingIdx = slotToIng.getInt(i)
                if (ingIdx < 0 || ingIdx >= ingredients.size) {
                    continue
                }
                val ingredient = ingredients[ingIdx]
                if (ingredient.isEmpty) {
                    continue
                }
                val slot = findMatchingSlot(inv, ingredient, consume)
                if (slot < 0) {
                    return null // 缺材料
                }
                grid[i] = inv.getItem(slot).copy()
                grid[i].setCount(1)
                consume[slot]++
            }

            val input = CraftingInput.of(width, height, grid.asList())
            if (!recipe.matches(input, maid.level())) {
                return null
            }
            return CraftResult(input, consume, recipe.assemble(input))
        }

        /** 找匹配 ingredient 且还有剩余量的背包槽（0-40） */
        private fun findMatchingSlot(inv: SimpleContainer, ingredient: Ingredient, consume: IntArray): Int {
            for (s in 0 until inv.getContainerSize()) {
                val stack = inv.getItem(s)
                if (!stack.isEmpty && ingredient.test(stack) && stack.getCount() > consume[s]) {
                    return s
                }
            }
            return -1
        }

        /**
         * 干跑查询（dry-run）：目标物品能不能合成、需要什么材料、女仆现有多少。
         *
         * **不消耗任何材料**，供桌宠在自动执行前判断、并向用户解释「差什么」。
         *
         * 返回结构（材料不足时 `craftable=false`，逐项给出 need/have/ok）：
         * ```
         * {"found":true,"craftable":false,"need":1,"crafts":1,"out_per_craft":1,
         *  "ingredients":[{"options":["minecraft:oak_planks",...],"need":3,"have":5,"ok":true},
         *                 {"options":["minecraft:stick"],"need":2,"have":0,"ok":false}]}
         * ```
         *
         * 候选配方优先取物品自带的 [DataComponents.RECIPES]（此时产物必然匹配，
         * 无需校验）；兜底全量扫描时用一个「填满候选项的 3x3 假网格」验证产物。
         */
        @JvmStatic
        fun check(maid: SmartMaidEntity, target: ItemStack?, count: Int): JsonObject {
            val out = JsonObject()
            out.addProperty("found", false)
            out.addProperty("craftable", false)
            out.add("ingredients", JsonArray())
            val t = target ?: return out
            if (t.isEmpty) {
                return out
            }
            if (maid.level().isClientSide) {
                return out
            }
            val level = maid.level() as? ServerLevel ?: return out
            val want = max(1, count)
            out.addProperty("need", want)

            val rm = level.recipeAccess()
            val candidates = ArrayList<RecipeHolder<CraftingRecipe>>()
            var trusted = false
            val keys = t.get(DataComponents.RECIPES)
            if (keys != null && !keys.isEmpty()) {
                trusted = true // 物品自带的配方 id：产物必然是该物品
                for (key in keys) {
                    rm.byKey(key).ifPresent { holder ->
                        if (holder.value() is CraftingRecipe) {
                            @Suppress("UNCHECKED_CAST")
                            val cr = holder as RecipeHolder<CraftingRecipe>
                            if (craftAllowed(cr, t)) {
                                candidates.add(cr)
                            }
                        }
                    }
                }
            } else {
                for (holder in allCraftingRecipes(rm)) {
                    if (craftAllowed(holder, t)) {
                        candidates.add(holder)
                    }
                }
            }
            if (candidates.isEmpty()) {
                return out
            }

            var best: JsonArray? = null
            var bestMissing = Int.MAX_VALUE
            var bestPerCraft = 1
            for (holder in candidates) {
                val recipe = holder.value()
                if (!trusted && !produces(maid, recipe, t)) {
                    continue
                }
                val perCraft = outputCount(recipe)
                val desc = describe(maid, recipe, want, perCraft)
                val missing = countMissing(desc)
                if (best == null || missing < bestMissing) {
                    best = desc
                    bestMissing = missing
                    bestPerCraft = perCraft
                }
                if (missing == 0 && trusted) {
                    break // 已可合成，无需再比
                }
            }
            val bestDesc = best ?: return out
            out.addProperty("found", true)
            out.addProperty("craftable", bestMissing == 0)
            out.addProperty("out_per_craft", bestPerCraft)
            out.addProperty("crafts", ceil(want.toDouble() / max(1, bestPerCraft)).toInt())
            out.add("ingredients", bestDesc)
            return out
        }

        /**
         * 逐项列出「需要多少 / 现有多少 / 够不够」。背包缺的直接材料若可递归合成
         * 补足则 `ok=true`，并附 `via_craft=true` 与 `craft_plan`
         * （需要先合成的中间物品列表），供桌宠向用户解释。
         */
        private fun describe(maid: SmartMaidEntity, recipe: CraftingRecipe, want: Int, perCraft: Int): JsonArray {
            val inv = maid.getMaidInventory()

            val inNeeds = ingredientNeeds(recipe)
            val ingredients = inNeeds.ingredients
            val perCraftNeed = inNeeds.need
            val crafts = ceil(want.toDouble() / max(1, perCraft)).toInt()

            val arr = JsonArray()
            for (idx in ingredients.indices) {
                if (perCraftNeed[idx] <= 0) {
                    continue
                }
                val ing = ingredients[idx]
                val item = JsonObject()
                val options = JsonArray()
                try {
                    ing.items().limit(MAX_OPTIONS.toLong()).forEach { holder: Holder<Item> ->
                        holder.unwrapKey().ifPresent { key -> options.add(key.identifier().toString()) }
                    }
                } catch (_: Throwable) {
                    // 取不到候选项不影响 need/have 判断
                }
                item.add("options", options)
                val need = perCraftNeed[idx] * crafts
                val have = countMatching(inv, ing)
                item.addProperty("need", need)
                item.addProperty("have", have)
                var ok = have >= need
                if (!ok) {
                    // 递归干跑：背包缺的直接材料能否由合成补足（含循环防护）
                    val plan = resolveMissing(maid, ing, need - have, 0, HashSet<Item>())
                    if (plan != null) {
                        ok = true
                        item.addProperty("via_craft", true)
                        val via = JsonArray()
                        plan.forEach { v -> via.add(v) }
                        item.add("craft_plan", via)
                    }
                }
                item.addProperty("ok", ok)
                arr.add(item)
            }
            return arr
        }

        /**
         * 干跑：ingredient 缺 `missing` 个，能否通过逐层合成补齐（不消耗材料）。
         *
         * @return 可补齐时返回「需要先合成的中间物品」列表（含缺口量，如
         *         `["minecraft:stick×2","minecraft:oak_planks×2"]`）；否则返回 null
         */
        private fun resolveMissing(
            maid: SmartMaidEntity,
            ing: Ingredient,
            missing: Int,
            depth: Int,
            visited: MutableSet<Item>
        ): List<String>? {
            if (depth > MAX_RECURSE_DEPTH) {
                return null
            }
            val candidates = ArrayList<Holder<Item>>()
            try {
                ing.items().limit(MAX_OPTIONS.toLong()).forEach { holder: Holder<Item> -> candidates.add(holder) }
            } catch (_: Throwable) {
            }
            for (holder in candidates) {
                val item = holder.value()
                if (visited.contains(item)) {
                    continue
                }
                val r = resolveItem(maid, item, missing, depth + 1, visited)
                if (r != null) {
                    return r
                }
            }
            return null
        }

        /**
         * 干跑：合成 `count` 个 item 的可行性解析（不消耗材料）。
         *
         * 递归检查该物品配方的每种材料：背包直接够的跳过；不够的递归
         * [resolveMissing]。全部可满足则返回「该物品自身 + 子链需要先合成的
         * 中间物」列表；任一分支无解 / 配方循环 / 超深度返回 null。
         */
        private fun resolveItem(
            maid: SmartMaidEntity,
            item: Item,
            count: Int,
            depth: Int,
            visited: MutableSet<Item>
        ): List<String>? {
            if (depth > MAX_RECURSE_DEPTH || visited.contains(item)) {
                return null
            }
            if (maid.level().isClientSide) {
                return null
            }
            val level = maid.level() as? ServerLevel ?: return null
            val rm = level.recipeAccess()
            val candidates = collectCandidates(maid, rm, ItemStack(item))
            if (candidates.isEmpty()) {
                return null
            }
            val next = HashSet(visited)
            next.add(item)
            val inv = maid.getMaidInventory()
            for (holder in candidates) {
                val recipe = holder.value()
                // 全量扫描路径必须验证产物 = item，否则会选中「材料可满足但产出不对」的配方
                if (!produces(maid, recipe, ItemStack(item))) {
                    continue
                }
                val perCraft = outputCount(recipe)
                val crafts = ceil(count.toDouble() / max(1, perCraft)).toInt()
                val inNeeds = ingredientNeeds(recipe)
                val need = inNeeds.need
                val ingredients = inNeeds.ingredients
                val subInputs = ArrayList<String>()
                var ok = true
                for (idx in need.indices) {
                    if (need[idx] <= 0) {
                        continue
                    }
                    val ing = ingredients[idx]
                    val have = countMatching(inv, ing)
                    val needN = need[idx] * crafts
                    if (have >= needN) {
                        continue // 背包直接够，不产生额外中间物
                    }
                    val sub = resolveMissing(maid, ing, needN - have, depth + 1, next)
                    if (sub == null) {
                        ok = false
                        break
                    }
                    subInputs.addAll(sub)
                }
                if (ok) {
                    val result = ArrayList<String>()
                    val id = BuiltInRegistries.ITEM.getKey(item).toString()
                    result.add(id + "\u00d7" + count) // 该物品自身需要被合成
                    result.addAll(subInputs)
                    return result
                }
            }
            return null
        }

        /** 统计描述里「不够」的条目数。 */
        private fun countMissing(desc: JsonArray): Int {
            var n = 0
            for (i in 0 until desc.size()) {
                val o = desc.get(i).getAsJsonObject()
                if (!o.has("ok") || !o.get("ok").getAsBoolean()) {
                    n++
                }
            }
            return n
        }

        /** 背包里匹配该 ingredient 的物品总数。 */
        private fun countMatching(inv: SimpleContainer, ing: Ingredient): Int {
            var total = 0
            for (s in 0 until inv.getContainerSize()) {
                val stack = inv.getItem(s)
                if (!stack.isEmpty && ing.test(stack)) {
                    total += stack.getCount()
                }
            }
            return total
        }

        /** 一次合成产出几个（用候选项填满的假网格 assemble 得到）。 */
        private fun outputCount(recipe: CraftingRecipe): Int {
            val input = filledInput(recipe) ?: return 1
            return try {
                max(1, recipe.assemble(input).getCount())
            } catch (_: Throwable) {
                1
            }
        }

        /** 全量扫描候选时，校验这条配方确实产出 target。 */
        private fun produces(maid: SmartMaidEntity, recipe: CraftingRecipe, target: ItemStack): Boolean {
            val input = filledInput(recipe) ?: return false
            return try {
                recipe.matches(input, maid.level())
                    && ItemStack.isSameItemSameComponents(recipe.assemble(input), target)
            } catch (_: Throwable) {
                false
            }
        }

        /** 用每个 ingredient 的第一个候选项搭一个假网格（按配方真实宽高，只看形状，不看数量）。 */
        private fun filledInput(recipe: CraftingRecipe): CraftingInput? {
            val info = recipe.placementInfo()
            val slotToIng = info.slotsToIngredientIndex()
            val ingredients = info.ingredients()
            val width = recipeWidth(recipe)
            val height = recipeHeight(recipe)
            val grid = Array(width * height) { ItemStack.EMPTY }
            val size = min(slotToIng.size, width * height)
            for (i in 0 until size) {
                val idx = slotToIng.getInt(i)
                if (idx < 0 || idx >= ingredients.size) {
                    continue
                }
                val ing = ingredients[idx]
                if (ing.isEmpty) {
                    continue
                }
                try {
                    grid[i] = ing.items().findFirst()
                        .map { holder: Holder<Item> -> ItemStack(holder, 1) }
                        .orElse(ItemStack.EMPTY)
                } catch (_: Throwable) {
                    // 保持 EMPTY
                }
            }
            return CraftingInput.of(width, height, grid.asList())
        }

        /** 配方行宽。ShapedRecipe 有明确宽高；ShapelessRecipe 位置无关，按 3 兜底。 */
        private fun recipeWidth(recipe: CraftingRecipe): Int {
            if (recipe is ShapedRecipe) {
                return max(1, recipe.getWidth())
            }
            return 3
        }

        /** 配方行高。ShapedRecipe 有明确宽高；ShapelessRecipe 位置无关，按 3 兜底。 */
        private fun recipeHeight(recipe: CraftingRecipe): Int {
            if (recipe is ShapedRecipe) {
                return max(1, recipe.getHeight())
            }
            return 3
        }
    }
}
