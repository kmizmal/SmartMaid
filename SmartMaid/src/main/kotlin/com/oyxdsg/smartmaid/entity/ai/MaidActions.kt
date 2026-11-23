package com.oyxdsg.smartmaid.entity.ai

import com.oyxdsg.smartmaid.compat.MaidCompat
import com.oyxdsg.smartmaid.data.SmartMaidConfig
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.food.FoodProperties
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.function.Predicate

/**
 * 女仆能力层（M1）：AI 任务可用的原子动作集合。
 *
 * <p>每个方法操作 {@link SmartMaidEntity}，全部带低频 debug 日志，服务端执行。</p>
 *
 * <p>与现有体系关系：移动复用 {@code navigation.moveTo}（跳跃由 {@link MaidActionExecutor}
 * 自动接管）；方块破坏用原版 {@code destroyBlock} 生成掉落物，靠女仆 {@code setCanPickUpLoot}
 * 自动拾取进背包（0-35）；方块放置借鉴车万女仆 null-player {@link BlockPlaceContext} 方案。</p>
 */
object MaidActions {

    /** 贵重/危险/特殊方块：搭路绝不消耗（矿物块、箱子、TNT、刷怪笼、基岩等） */
    private val VALUABLE_BLOCKS: Set<Block> = HashSet(listOf(
        Blocks.GOLD_BLOCK, Blocks.IRON_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK,
        Blocks.NETHERITE_BLOCK,
        Blocks.RAW_IRON_BLOCK, Blocks.RAW_GOLD_BLOCK, Blocks.RAW_COPPER_BLOCK,
        Blocks.LAPIS_BLOCK, Blocks.REDSTONE_BLOCK, Blocks.COAL_BLOCK,
        Blocks.QUARTZ_BLOCK, Blocks.AMETHYST_BLOCK,
        Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.SHULKER_BOX,
        Blocks.TNT, Blocks.RESPAWN_ANCHOR, Blocks.BEACON, Blocks.CONDUIT,
        Blocks.SPAWNER, Blocks.DRAGON_EGG, Blocks.BEDROCK,
        Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
        Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
        Blocks.JUKEBOX, Blocks.CARTOGRAPHY_TABLE, Blocks.GRINDSTONE, Blocks.LOOM))

    // ---------- 移动 ----------

    /** 寻路到目标格中心（跳跃自动生效，无需额外处理） */
    @JvmStatic
    fun navigateTo(maid: SmartMaidEntity, pos: BlockPos, speed: Double): Boolean {
        if (maid.level().isClientSide) {
            return false
        }
        return maid.getNavigation().moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, speed)
    }

    /** 距目标格中心的水平距离是否在 range 内 */
    @JvmStatic
    fun isWithinReach(maid: SmartMaidEntity, pos: BlockPos, range: Double): Boolean {
        val dx = maid.x - (pos.x + 0.5)
        val dz = maid.z - (pos.z + 0.5)
        return dx * dx + dz * dz <= range * range
    }

    // ---------- 背包操作（41 格：0-8 热键 / 9-35 背包 / 36-39 盔甲 / 40 副手） ----------

    /** 在背包 0-40 查找符合条件物品的槽位（-1 = 未找到） */
    @JvmStatic
    fun findInBackpack(maid: SmartMaidEntity, predicate: Predicate<ItemStack>): Int {
        val inv = maid.getMaidInventory()
        for (i in 0 until inv.containerSize) {
            if (predicate.test(inv.getItem(i))) {
                return i
            }
        }
        return -1
    }

    /**
     * 从背包取符合条件物品放到主手（热键0，与槽0交换），并同步装备栏。
     *
     * @return true 表示主手已持有目标物品
     */
    @JvmStatic
    fun equipFromBackpack(maid: SmartMaidEntity, predicate: Predicate<ItemStack>): Boolean {
        val inv = maid.getMaidInventory()
        if (predicate.test(inv.getItem(0))) {
            return true
        }
        val slot = findInBackpack(maid, predicate)
        if (slot <= 0) {
            return false
        }
        val hand = inv.getItem(0)
        inv.setItem(0, inv.getItem(slot))
        inv.setItem(slot, hand)
        inv.setChanged()
        maid.syncInventoryArmor()
        MaidDebug.log("equip: 背包槽 " + slot + " -> 主手")
        return true
    }

    /**
     * 把物品收纳进背包区（0-35）：先同类堆叠，再找空槽；返回未放下的剩余。
     *
     * <p>若当下放不下，先让 {@link MaidInventoryTidy} 整理一次背包（合并同类 + 丢垃圾），
     * 再重试一次 —— 否则"背包碎片化/塞满"会让掉落物一直留在原地。</p>
     */
    @JvmStatic
    fun storeToBackpack(maid: SmartMaidEntity, stack: ItemStack): ItemStack {
        val inv = maid.getMaidInventory()
        var remaining = tryInsert(inv, stack)
        if (!remaining.isEmpty && MaidInventoryTidy.tidyIfStuck(maid)) {
            remaining = tryInsert(inv, remaining)
        }
        inv.setChanged()
        return remaining
    }

    /** 单次放入尝试：先同类堆叠，再找空槽；返回未放下的剩余。 */
    private fun tryInsert(inv: SimpleContainer, stack: ItemStack): ItemStack {
        var remaining = stack.copy()
        for (i in 0 until 36) {
            if (remaining.isEmpty) {
                break
            }
            val existing = inv.getItem(i)
            if (!existing.isEmpty && ItemStack.isSameItemSameComponents(existing, remaining)
                && existing.count < existing.maxStackSize
            ) {
                val room = Math.min(remaining.count, existing.maxStackSize - existing.count)
                existing.grow(room)
                remaining.shrink(room)
            }
        }
        for (i in 0 until 36) {
            if (remaining.isEmpty) {
                break
            }
            if (inv.getItem(i).isEmpty) {
                inv.setItem(i, remaining)
                remaining = ItemStack.EMPTY
            }
        }
        return remaining
    }

    /** 是否为可食用食物（有 FOOD data component） */
    @JvmStatic
    fun isFood(stack: ItemStack): Boolean {
        val food: FoodProperties? = stack.get(DataComponents.FOOD)
        return food != null
    }

    /**
     * 找食物槽：{@code itemFilter} 为空时选营养最高（其次饱和度最高）的食物；否则选该物品。
     *
     * @return 背包槽位（-1 = 未找到）
     */
    @JvmStatic
    fun findFoodSlot(maid: SmartMaidEntity, itemFilter: Item?): Int {
        val inv = maid.getMaidInventory()
        var bestSlot = -1
        var bestNutrition = -1
        var bestSaturation = -1.0f
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            if (itemFilter != null && !stack.`is`(itemFilter)) {
                continue
            }
            val food: FoodProperties = stack.get(DataComponents.FOOD) ?: continue
            if (food.nutrition() > bestNutrition
                || (food.nutrition() == bestNutrition && food.saturation() > bestSaturation)
            ) {
                bestNutrition = food.nutrition()
                bestSaturation = food.saturation()
                bestSlot = i
            }
        }
        return bestSlot
    }

    /** 把指定背包槽换到主手（热键 0）并同步装备栏。 */
    @JvmStatic
    fun swapToMainhand(maid: SmartMaidEntity, slot: Int): Boolean {
        val inv = maid.getMaidInventory()
        if (slot < 0 || slot >= inv.containerSize) {
            return false
        }
        if (slot != 0) {
            val hand = inv.getItem(0)
            inv.setItem(0, inv.getItem(slot))
            inv.setItem(slot, hand)
            inv.setChanged()
        }
        maid.syncInventoryArmor()
        return true
    }

    /** 开始进食主手食物（原版 startUsingItem；LivingEntity.tick 自动推进并在完成时消费）。 */
    @JvmStatic
    fun startEating(maid: SmartMaidEntity): Boolean {
        if (maid.mainHandItem.get(DataComponents.FOOD) == null) {
            return false
        }
        maid.startUsingItem(InteractionHand.MAIN_HAND)
        return true
    }

    // ---------- 方块操作 ----------

    /**
     * 从女仆眼睛向目标方块中心做射线，返回命中的**第一个方块**（含目标自身）。
     * 玩家挖掘的本质就是「准星命中哪个方块挖哪个」——被挡时命中的是挡路块，不是目标。
     *
     * @return 命中的方块 pos；未命中（目标已空/路径全空）返回 null
     */
    @JvmStatic
    fun lineHit(maid: SmartMaidEntity, pos: BlockPos): BlockPos? {
        if (maid.level().isClientSide) {
            return null
        }
        val eye = maid.eyePosition
        val target = Vec3.atCenterOf(pos)
        val hit: BlockHitResult = maid.level().clip(ClipContext(
            eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, maid
        ))
        if (hit.type == HitResult.Type.BLOCK) {
            return hit.blockPos
        }
        return null
    }

    /** 背包（含主手）是否持有可放置方块物品 */
    @JvmStatic
    fun hasBlockItem(maid: SmartMaidEntity): Boolean {
        return maid.mainHandItem.item is BlockItem ||
            findInBackpack(maid) { s -> s.item is BlockItem } >= 0
    }

    /** 从背包找任意方块物品换到主手（搭路/建造前置）；找不到返回 false */
    @JvmStatic
    fun equipBlockFromBackpack(maid: SmartMaidEntity): Boolean {
        if (maid.mainHandItem.item is BlockItem) {
            return true
        }
        return equipFromBackpack(maid) { s -> s.item is BlockItem }
    }

    // ---------- 搭路方块选择（只用普通建材，不浪费贵重物品） ----------

    /** 搭路白名单：只有该 tag 内的方块才允许被消耗。数据包可覆盖（data/smartmaid/tags/item/bridge_blocks.json） */
    private val BRIDGE_BLOCKS: TagKey<Item> =
        TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("smartmaid", "bridge_blocks"))

    /**
     * 是否为可用来搭路的方块。
     *
     * <p><b>2026-09-29 改造：黑名单 → 白名单。</b>原逻辑是"不在 {@link #VALUABLE_BLOCKS} 黑名单里的都能用"，
     * 而黑名单是封闭集合 —— 整合包新增的贵重方块（其它 mod 的矿物块 / 机器 / 容器）不在名单内，
     * 会被女仆当砖头消耗掉。现在只有白名单 tag 内的便宜建材可用，未知方块一律安全。</p>
     *
     * <p>兼容：白名单 tag 为空时回退到旧的黑名单行为，避免数据包异常导致女仆完全无法搭路。</p>
     */
    @JvmStatic
    fun isBridgeBlock(stack: ItemStack): Boolean {
        if (stack.isEmpty) {
            return false
        }
        val blockItem = stack.item as? BlockItem ?: return false
        val block = blockItem.block
        if (SmartMaidConfig.bridgeBlocksBlacklist().contains(block)) {
            return false // config 追加黑名单优先级最高
        }
        if (stack.`is`(BRIDGE_BLOCKS)) {
            return true
        }
        // 回退（白名单 tag 未加载/为空）：保持旧行为，避免"数据包出事 → 女仆不能搭路"
        val whitelistEmpty =
            !BuiltInRegistries.ITEM.getTagOrEmpty(BRIDGE_BLOCKS).iterator().hasNext()
        return whitelistEmpty && !VALUABLE_BLOCKS.contains(block)
    }

    /** 搭路方块优先级：分数越小越优先消耗（0=泥土/圆石/石头等最普通；重力方块排最后） */
    private fun bridgePriority(block: Block): Int {
        if (block == Blocks.DIRT || block == Blocks.GRASS_BLOCK || block == Blocks.COBBLESTONE
            || block == Blocks.STONE || block == Blocks.COBBLED_DEEPSLATE
            || block == Blocks.DEEPSLATE || block == Blocks.MUD
        ) {
            return 0
        }
        if (block == Blocks.SAND || block == Blocks.GRAVEL) {
            return 9 // 重力方块悬空会下落，最后才用
        }
        return 3 // 木板/原木/花岗岩等普通建材
    }

    /** 背包（含主手）是否有可搭路的普通方块 */
    @JvmStatic
    fun hasBridgeBlock(maid: SmartMaidEntity): Boolean {
        return isBridgeBlock(maid.mainHandItem) ||
            findInBackpack(maid) { s -> isBridgeBlock(s) } >= 0
    }

    /**
     * 从背包按优先级挑一个可搭路的普通方块换到主手（越普通/数量越多越优先）。
     *
     * @return true 表示主手已持有可搭路方块
     */
    @JvmStatic
    fun equipBridgeBlockFromBackpack(maid: SmartMaidEntity): Boolean {
        if (isBridgeBlock(maid.mainHandItem)) {
            return true
        }
        val inv = maid.getMaidInventory()
        var bestSlot = -1
        var bestScore = Int.MAX_VALUE
        for (i in 0 until inv.containerSize) {
            val s = inv.getItem(i)
            if (!isBridgeBlock(s)) {
                continue
            }
            val block = (s.item as BlockItem).block
            // 优先级为主，数量次之（同优先级先消耗数量多的）
            val score = bridgePriority(block) * 100 - Math.min(s.count, 99)
            if (score < bestScore) {
                bestScore = score
                bestSlot = i
            }
        }
        if (bestSlot < 0) {
            return false
        }
        val hand = inv.getItem(0)
        inv.setItem(0, inv.getItem(bestSlot))
        inv.setItem(bestSlot, hand)
        inv.setChanged()
        maid.syncInventoryArmor()
        MaidDebug.log("equipBridgeBlock: 槽 " + bestSlot + " -> 主手 " + MaidDebug.itemName(inv.getItem(0)))
        return true
    }

    /**
     * 破坏指定方块并把掉落物直接收进背包（0-35）。
     *
     * <p><b>2026-09-29 改造（整合包兼容）</b>：不再绕过原版破坏流程。按玩家破坏的顺序补齐五步：</p>
     * <ol>
     *   <li>{@code PlayerBlockBreakEvents.BEFORE} —— 其他 mod / 领地保护可以返回 false 否决；</li>
     *   <li>{@code Block.playerWillDestroy} —— mod 机器/容器的数据保存钩子（返回值可能替换方块状态）；</li>
     *   <li>{@code level.destroyBlock(...)} —— 原版掉落 + gameEvent + 音效粒子 + 邻居更新；</li>
     *   <li>掉落自行收进背包（保持"进背包"的既有设计，drop=false 避免散落一地）；</li>
     *   <li>{@code PlayerBlockBreakEvents.AFTER}。</li>
     * </ol>
     *
     * <p>身份由 {@link MaidActor} 提供：以主人的 FakePlayer 执行，使领地/权限/统计按主人判定。</p>
     */
    @JvmStatic
    fun breakBlock(maid: SmartMaidEntity, pos: BlockPos): Boolean {
        if (maid.level().isClientSide) {
            return false
        }
        val level = maid.level() as? ServerLevel ?: return false
        val state = level.getBlockState(pos)
        if (state.isAir) {
            return false
        }
        if (!isWithinReach(maid, pos, 4.0)) {
            return false
        }
        val actor = MaidActor.actorFor(maid) ?: return false
        val blockEntity: BlockEntity? = if (state.hasBlockEntity()) level.getBlockEntity(pos) else null

        // ① 破坏前事件：其他 mod / 领地保护可返回 false 否决（女仆会放弃这个方块）
        if (!PlayerBlockBreakEvents.BEFORE.invoker()
                .beforeBlockBreak(level, actor, pos, state, blockEntity)
        ) {
            MaidDebug.log("breakBlock 被事件否决 " + pos)
            return false
        }

        // ② mod 方块的破坏钩子。返回值与原 state 不同 => "方块没被破坏、只是变了状态"
        //    （典型：双半砖挖掉一半），此时不执行后续的移除与掉落。
        val willDestroy = state.block.playerWillDestroy(level, pos, state, actor)
        if (willDestroy !== state) {
            level.setBlock(pos, willDestroy, 3)
            PlayerBlockBreakEvents.AFTER.invoker()
                .afterBlockBreak(level, actor, pos, willDestroy, blockEntity)
            MaidCompat.swing(maid, InteractionHand.MAIN_HAND)
            MaidDebug.log("breakBlock 状态变更 " + pos + " -> " + willDestroy)
            return true
        }

        MaidCompat.swing(maid, InteractionHand.MAIN_HAND)
        val tool = maid.mainHandItem
        if (!tool.isEmpty) {
            tool.hurtAndBreak(1, maid, EquipmentSlot.MAINHAND)
        }

        // ③ 原版破坏：drop=false（掉落自己收）→ ④ 掉落进背包，背包满则掉在原地
        val removed = level.destroyBlock(pos, false, actor, 512)
        if (removed) {
            for (drop in Block.getDrops(state, level, pos, blockEntity, actor, tool)) {
                val left = storeToBackpack(maid, drop)
                if (!left.isEmpty) {
                    Block.popResource(level, pos, left)
                }
            }
        }

        // ⑤ 破坏后事件
        PlayerBlockBreakEvents.AFTER.invoker()
            .afterBlockBreak(level, actor, pos, state, blockEntity)
        MaidDebug.log("breakBlock " + pos + " " + state.block + " -> " + removed)
        return removed
    }

    /**
     * 挖掘前自动换"最优工具"（对齐玩家挖掘判定）：从背包选对目标方块
     * {@code isCorrectToolForDrops} 且挖掘速度最高的工具换到主手。
     * 26.2 已移除 Tier 等级与 DiggerItem，改由 {@code Tool} 组件驱动——
     * 用「对目标方块的挖掘速度」作为最优判据（速度高即材料等级高，金/铜更快）。
     * 每挖一个方块判定一次，保证换手（石头→镐、泥土→铲、木头→斧）。
     *
     * <p>无正确工具时保持徒手，符合玩家判定（徒手挖需工具的方块不掉落）。</p>
     *
     * @return true 表示主手已持有对目标方块有效的工具
     */
    @JvmStatic
    fun equipBestToolFor(maid: SmartMaidEntity, state: BlockState): Boolean {
        val inv = maid.getMaidInventory()
        var bestSlot = -1
        var bestSpeed = -1.0f
        for (i in 0 until inv.containerSize) {
            val s = inv.getItem(i)
            if (s.isEmpty || !s.isCorrectToolForDrops(state)) {
                continue
            }
            val speed = s.getDestroySpeed(state)
            if (speed > bestSpeed) {
                bestSpeed = speed
                bestSlot = i
            }
        }
        if (bestSlot < 0) {
            MaidDebug.log("equipBestTool: 背包无正确工具，徒手挖 " + state.block)
            return false
        }
        // 主手已是正确工具且速度不低于背包最优 → 不换
        val hand = inv.getItem(0)
        if (hand.isCorrectToolForDrops(state) && hand.getDestroySpeed(state) >= bestSpeed) {
            return true
        }
        inv.setItem(0, inv.getItem(bestSlot))
        inv.setItem(bestSlot, hand)
        inv.setChanged()
        // 无条件强制同步主手到客户端（setItemInHand 走 setItemSlot，绕过 syncSlot 的
        // isSameItemSameComponents 跳过判断），保证渲染层立即显示换好的工具
        maid.setItemInHand(InteractionHand.MAIN_HAND, inv.getItem(0))
        maid.syncInventoryArmor()
        MaidDebug.log("equipBestTool: 槽 " + bestSlot + " -> 主手 " + MaidDebug.itemName(inv.getItem(0)))
        return true
    }

    /**
     * 计算挖掘目标方块所需的总 tick（与玩家挖掘速度一致）：
     * 挖掘 tick = hardness × 100 / digSpeed（非创造，1 硬度徒手 ≈ 5 秒 = 100 tick；
     * 拿对工具 isCorrectToolForDrops 时按工具速度加速）。至少 5 tick 保证有挖掘过程。
     *
     * @return -1 表示方块不可破坏
     */
    @JvmStatic
    fun computeMiningTicks(maid: SmartMaidEntity, pos: BlockPos): Int {
        val state = maid.level().getBlockState(pos)
        val hardness = state.getDestroySpeed(maid.level(), pos)
        if (hardness < 0.0f || state.isAir) {
            return -1
        }
        if (hardness == 0.0f) {
            return 1
        }
        var ticks = Math.ceil(hardness * 100.0) // 徒手基准：1 硬度 ≈ 5 秒
        val tool = maid.mainHandItem
        if (!tool.isEmpty && tool.isCorrectToolForDrops(state)) {
            val speed = tool.getDestroySpeed(state)
            if (speed > 0.0f) {
                ticks = Math.ceil(hardness * 100.0 / speed)
            }
        }
        return Math.max(5, ticks.toInt())
    }

    /** 显示/更新方块挖掘裂纹进度（stage 0-9；传 -1 清除裂纹） */
    @JvmStatic
    fun showMiningProgress(maid: SmartMaidEntity, pos: BlockPos, stage: Int) {
        val level = maid.level() as? ServerLevel ?: return
        level.destroyBlockProgress(maid.id, pos, Math.max(-1, Math.min(9, stage)))
    }

    /**
     * 手持方块放置到目标格。
     *
     * <p><b>2026-09-29 改造（整合包兼容）</b>：改用公开构造
     * {@code BlockPlaceContext(Player, InteractionHand, ItemStack, BlockHitResult)}，
     * 以 {@link MaidActor}（主人的 FakePlayer）作玩家上下文。原先传 {@code null} 玩家
     * 虽然原版能容忍，但第三方 mod 的 {@link BlockItem} 子类常直接访问 player
     * （潜行判定 / 归属 / 统计）→ NPE 崩溃。</p>
     *
     * <p>注意：这里传的仍是 {@code maid.getMainHandItem()}，扣减作用在女仆手上
     * （不能传 actor 的手持，否则会扣错人的物品）。</p>
     */
    @JvmStatic
    fun placeBlock(maid: SmartMaidEntity, pos: BlockPos, dir: Direction): Boolean {
        val stack = maid.mainHandItem
        val blockItem = stack.item as? BlockItem ?: return false
        val actor = MaidActor.actorFor(maid) ?: return false
        val hit = BlockHitResult(
            Vec3.atCenterOf(pos).add(dir.stepX * 0.5, dir.stepY * 0.5, dir.stepZ * 0.5),
            dir, pos, false
        )
        val result: InteractionResult = blockItem.place(
            BlockPlaceContext(actor, InteractionHand.MAIN_HAND, stack, hit)
        )
        val ok = result.consumesAction()
        MaidDebug.log("placeBlock " + pos + " " + stack.item.descriptionId + " -> " + ok)
        return ok
    }

    /**
     * 右键使用手持物品于目标格（播种/火把/骨粉等）。
     *
     * <p><b>2026-09-29 改造</b>：同 {@link #placeBlock} —— 用主人的 FakePlayer 代替 null 玩家。
     * 这里走 {@code stack.useOn(ctx)}：直接用女仆手上的 stack，actor 的手持已在
     * {@link MaidActor#actorFor} 里同步，所以 mod 内部读 {@code player.getItemInHand()}
     * 也能拿到同一个物品。</p>
     */
    @JvmStatic
    fun useItemOn(maid: SmartMaidEntity, pos: BlockPos, dir: Direction): Boolean {
        val stack = maid.mainHandItem
        if (stack.isEmpty) {
            return false
        }
        val actor = MaidActor.actorFor(maid) ?: return false
        val hit = BlockHitResult(
            Vec3.atCenterOf(pos).add(dir.stepX * 0.5, dir.stepY * 0.5, dir.stepZ * 0.5),
            dir, pos, false
        )
        val result: InteractionResult = stack.useOn(
            UseOnContext(actor, InteractionHand.MAIN_HAND, hit)
        )
        val ok = result.consumesAction()
        MaidDebug.log("useItemOn " + pos + " " + stack.item.descriptionId + " -> " + ok)
        return ok
    }

    // ---------- 战斗 ----------

    /** 近战攻击目标（注意 26.2 签名：doHurtTarget 需要 ServerLevel 参数） */
    @JvmStatic
    fun attack(maid: SmartMaidEntity, target: LivingEntity?): Boolean {
        if (target == null || !target.isAlive) {
            return false
        }
        if (maid.level().isClientSide) {
            return false
        }
        val level = maid.level() as? ServerLevel ?: return false
        MaidCompat.swing(maid, InteractionHand.MAIN_HAND)
        val ok = maid.doHurtTarget(level, target)
        MaidDebug.log("attack " + target.type.toShortString() + " -> " + ok)
        return ok
    }

    // ---------- 实体查找 ----------

    /** 在范围内按类型 + 谓词查找实体列表（索敌/找掉落物） */
    @JvmStatic
    fun <T : Entity> findEntities(
        maid: SmartMaidEntity,
        clazz: Class<T>,
        range: Double,
        predicate: Predicate<T>
    ): List<T> {
        val box: AABB = maid.boundingBox.inflate(range)
        return maid.level().getEntities(EntityTypeTest.forClass(clazz), box, predicate)
    }

    /** 找最近的匹配实体 */
    @JvmStatic
    fun <T : Entity> findNearestEntity(
        maid: SmartMaidEntity,
        clazz: Class<T>,
        range: Double,
        predicate: Predicate<T>
    ): T? {
        return findEntities(maid, clazz, range, predicate).minByOrNull { e -> maid.distanceToSqr(e) }
    }

    /** 找最近的掉落物 */
    @JvmStatic
    fun findNearestItem(maid: SmartMaidEntity, range: Double, itemFilter: Predicate<ItemStack>?): ItemEntity? {
        return findNearestEntity(maid, ItemEntity::class.java, range) { e ->
            e.isAlive && (itemFilter == null || itemFilter.test(e.item))
        }
    }

    // ---------- 其他 ----------

    /** 向 target 位置生成投掷物品实体（喂食"丢出来"） */
    @JvmStatic
    fun throwItem(maid: SmartMaidEntity, stack: ItemStack, target: LivingEntity, speed: Double) {
        if (maid.level().isClientSide) {
            return
        }
        val level: Level = maid.level()
        val eye = maid.eyePosition
        val to = target.eyePosition.subtract(eye).normalize()
        val spawn = eye.add(to.scale(0.6))
        val itemEntity = ItemEntity(level, spawn.x, spawn.y, spawn.z, stack)
        itemEntity.deltaMovement = to.scale(speed)
        itemEntity.setNoPickUpDelay()
        level.addFreshEntity(itemEntity)
    }

    /**
     * 把物品丢到**主人身边**（主人脚下），并让女仆短时间内不去拾取。
     *
     * <p>用于「把 X 丢给我」这类给物品场景：丢在女仆自己脚下会被她的拾取逻辑
     * （{@code setCanPickUpLoot(true)} + {@code wantsToPickUp} 恒真）立刻收进背包，
     * 等于没给。丢到主人所在位置后主人能直接捡到；同时 {@link SmartMaidEntity#suppressPickup(int)}
     * 让女仆 5 秒内不捡，避免抢走。没有主人时退回丢在自己脚下。</p>
     */
    @JvmStatic
    fun dropToOwner(maid: SmartMaidEntity, stack: ItemStack?) {
        if (stack == null || stack.isEmpty) {
            return
        }
        val level: Level = maid.level()
        val owner: LivingEntity? = maid.owner
        val x = owner?.x ?: maid.x
        val y = (owner?.y ?: maid.y) + 0.25
        val z = owner?.z ?: maid.z
        val item = ItemEntity(level, x, y, z, stack)
        item.setNoPickUpDelay()
        level.addFreshEntity(item)
        maid.suppressPickup(100)
        MaidDebug.log(
            "dropToOwner " + stack.item.descriptionId
                + " x" + stack.count + " -> "
                + (owner?.name?.string ?: "self")
        )
    }
}
