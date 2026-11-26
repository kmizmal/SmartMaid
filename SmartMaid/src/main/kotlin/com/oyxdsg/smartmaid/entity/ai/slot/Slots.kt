package com.oyxdsg.smartmaid.entity.ai.slot

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.Container
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

object Slots {
    /** 主手槽（背包热键第 0 格） */
    @JvmStatic
    fun mainhand(): ItemSlot = MaidSlot(0)

    /** 指定容器位置槽位（slot=-1 表示 auto：插入找空槽、提取找首个非空） */
    @JvmStatic
    fun container(pos: BlockPos, slot: Int): ItemSlot = ContainerSlot(pos, slot)

    /** 判断槽位是否为"地面"（world：只能作转移目标，不能作源） */
    @JvmStatic
    fun isWorldSlot(slot: ItemSlot): Boolean = slot is WorldSlot

    /** 解析槽位表达式；无法解析返回 null */
    @JvmStatic
    fun parse(spec: String?): ItemSlot? {
        if (spec == null) {
            return null
        }
        // 兼容中文输入法的全角符号：：，、全角空格
        val s = spec.trim()
            .replace('：', ':')
            .replace('，', ',')
            .replace('\u3000', ' ')
            .replace('－', '-')
            .trim()
        when (s) {
            "mainhand" -> return MaidSlot(0)
            "offhand" -> return MaidSlot(40)
            "head" -> return MaidSlot(36)
            "chest" -> return MaidSlot(37)
            "legs" -> return MaidSlot(38)
            "feet" -> return MaidSlot(39)
        }
        if (s.startsWith("inv:")) {
            return try {
                maidSlot(s.substring(4).toInt())
            } catch (_: NumberFormatException) {
                null
            }
        }
        // 宽容写法：inv5 或纯数字 5（都指背包槽位 5）
        if (s.startsWith("inv")) {
            return try {
                maidSlot(s.substring(3).toInt())
            } catch (_: NumberFormatException) {
                null
            }
        }
        try {
            return maidSlot(s.toInt())
        } catch (_: NumberFormatException) {
            // 继续尝试其他类型
        }
        if (s.startsWith("world:")) {
            val xyz = parseXYZ(s.substring(6))
            return if (xyz == null) null else WorldSlot(BlockPos(xyz[0], xyz[1], xyz[2]))
        }
        if (s.startsWith("container:")) {
            val rest = s.substring(10)
            val colon = rest.lastIndexOf(':')
            if (colon > 0 && rest.indexOf(',') < colon) {
                val xyz = parseXYZ(rest.substring(0, colon)) ?: return null
                val slotPart = rest.substring(colon + 1)
                val slot = if (slotPart == "auto") -1 else slotPart.toInt()
                return ContainerSlot(BlockPos(xyz[0], xyz[1], xyz[2]), slot)
            }
            val xyz = parseXYZ(rest)
            return if (xyz == null) null else ContainerSlot(BlockPos(xyz[0], xyz[1], xyz[2]), -1)
        }
        return null
    }

    /** 女仆背包只有 0-40 共 41 格；越界在解析阶段拒绝，避免任务线程访问无效槽位。 */
    private fun maidSlot(index: Int): ItemSlot? =
        if (index in 0..40) MaidSlot(index) else null

    private fun parseXYZ(s: String): IntArray? {
        val parts = s.split(",")
        if (parts.size != 3) {
            return null
        }
        return try {
            intArrayOf(parts[0].trim().toInt(), parts[1].trim().toInt(), parts[2].trim().toInt())
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** 女仆自身槽位（背包/手持/盔甲，全部映射到 41 格 SimpleContainer） */
    private class MaidSlot(private val index: Int) : ItemSlot {
        override fun reachPos(): BlockPos? = null // 女仆自身，无需移动

        override fun canAccept(maid: SmartMaidEntity, stack: ItemStack): Boolean {
            // 盔甲槽（36-39）只接受对应部位的装备，否则 transfer 掉落而非硬塞
            if (index in 36..39 && !stack.isEmpty) {
                val equipmentSlot = when (index) {
                    36 -> net.minecraft.world.entity.EquipmentSlot.HEAD
                    37 -> net.minecraft.world.entity.EquipmentSlot.CHEST
                    38 -> net.minecraft.world.entity.EquipmentSlot.LEGS
                    else -> net.minecraft.world.entity.EquipmentSlot.FEET
                }
                return maid.isEquippableInSlot(stack, equipmentSlot)
            }
            return true
        }

        override fun peek(maid: SmartMaidEntity): ItemStack = maid.getMaidInventory().getItem(index)

        override fun extract(maid: SmartMaidEntity, count: Int): ItemStack {
            val inv = maid.getMaidInventory()
            val stack = inv.getItem(index)
            if (stack.isEmpty) {
                return ItemStack.EMPTY
            }
            val result = inv.removeItem(index, minOf(count, stack.count))
            inv.setChanged()
            return result
        }

        override fun insert(maid: SmartMaidEntity, stack: ItemStack): ItemStack {
            val inv = maid.getMaidInventory()
            val work = stack.copy()
            val existing = inv.getItem(index)
            if (existing.isEmpty) {
                inv.setItem(index, work)
                inv.setChanged()
                return ItemStack.EMPTY
            }
            if (ItemStack.isSameItemSameComponents(existing, work)) {
                val room = existing.maxStackSize - existing.count
                val move = minOf(room, work.count)
                existing.grow(move)
                work.shrink(move)
                inv.setChanged()
            }
            return work // 槽满或占用不同物品 → 剩余原样返回
        }

        override fun toString(): String {
            val name = when (index) {
                0 -> "mainhand"
                36 -> "head"
                37 -> "chest"
                38 -> "legs"
                39 -> "feet"
                40 -> "offhand"
                else -> index.toString()
            }
            return "槽位[$name]"
        }
    }

    /**
     * 地面槽位：只作为<b>转移目标</b>（放置方块 / 丢出物品）。
     *
     * <p><b>不支持作为源</b>：地面→背包的拾取必须由女仆走过去通过原版机制完成
     * （见 {@code collect} / {@code pickup} 指令），避免"凭空吸物"的观感问题。</p>
     */
    private class WorldSlot(private val pos: BlockPos) : ItemSlot {
        override fun reachPos(): BlockPos = pos

        override fun peek(maid: SmartMaidEntity): ItemStack = ItemStack.EMPTY // 地面不作读取源

        override fun extract(maid: SmartMaidEntity, count: Int): ItemStack =
            ItemStack.EMPTY // 地面不作转移源，拾取走 collect/pickup（有走过去的过程）

        override fun toString(): String = "地面$pos"

        override fun insert(maid: SmartMaidEntity, stack: ItemStack): ItemStack {
            if (stack.isEmpty) {
                return ItemStack.EMPTY
            }
            val level: Level = maid.level()
            // 方块物品 → 放置到目标格（place 成功会内部 shrink，返回剩余）
            if (stack.item is BlockItem) {
                val blockItem = stack.item as BlockItem
                val hit = BlockHitResult(
                    Vec3.atCenterOf(pos).add(0.5, 0.5, 0.5),
                    net.minecraft.core.Direction.UP,
                    pos,
                    false
                )
                blockItem.place(BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, stack, hit))
                return stack
            }
            // 非方块 → 生成掉落物丢出
            val item = ItemEntity(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, stack)
            item.setNoPickUpDelay()
            level.addFreshEntity(item)
            // 丢出后女仆短时间内不捡（避免「丢给主人又马上被自己捡回」）
            maid.suppressPickup(100)
            return ItemStack.EMPTY
        }
    }

    /** 容器槽位（箱子/熔炉等 Container 方块实体）；slot=-1 表示 auto */
    private class ContainerSlot(private val pos: BlockPos, private val slot: Int) : ItemSlot {
        override fun reachPos(): BlockPos = pos

        override fun peek(maid: SmartMaidEntity): ItemStack {
            val container = getContainer(maid) ?: return ItemStack.EMPTY
            val idx = resolveSlot(container, slot, false)
            return if (idx < 0) ItemStack.EMPTY else container.getItem(idx)
        }

        override fun extract(maid: SmartMaidEntity, count: Int): ItemStack {
            val container = getContainer(maid) ?: return ItemStack.EMPTY
            val idx = resolveSlot(container, slot, false)
            if (idx < 0) {
                return ItemStack.EMPTY
            }
            val stack = container.getItem(idx)
            if (stack.isEmpty) {
                return ItemStack.EMPTY
            }
            val result = container.removeItem(idx, minOf(count, stack.count))
            container.setChanged()
            return result
        }

        override fun isSwapAllowed(): Boolean = false // 容器满时不取出内容交换

        override fun insert(maid: SmartMaidEntity, stack: ItemStack): ItemStack {
            val container = getContainer(maid) ?: return stack.copy() // 无容器（可能未走到/位置不对）
            val work = stack.copy()
            val size = container.containerSize
            // 1. 优先：同类且未满的格子（智能堆叠，自动按 maxStackSize 判断 64/16/1）
            for (i in 0 until size) {
                val existing = container.getItem(i)
                if (!existing.isEmpty && ItemStack.isSameItemSameComponents(existing, work)
                    && existing.count < existing.maxStackSize
                ) {
                    val room = existing.maxStackSize - existing.count
                    val move = minOf(room, work.count)
                    existing.grow(move)
                    work.shrink(move)
                    container.setChanged()
                    if (work.isEmpty) {
                        return ItemStack.EMPTY
                    }
                }
            }
            // 2. 其次：空槽（含不可堆叠物品如盔甲的单放）
            for (i in 0 until size) {
                if (container.getItem(i).isEmpty) {
                    container.setItem(i, work)
                    container.setChanged()
                    return ItemStack.EMPTY
                }
            }
            // 3. 容器满 → 返回剩余（TransferTask 会放回源并提示，不掉落）
            return work
        }

        override fun toString(): String = "容器$pos${if (slot < 0) "[auto]" else "[槽$slot]"}"

        private fun getContainer(maid: SmartMaidEntity): Container? {
            val be: BlockEntity? = maid.level().getBlockEntity(pos)
            return be as? Container
        }

        /**
         * 解析实际槽位下标。auto 时：目标找空槽/同类堆叠；源找首个非空槽。
         *
         * @param wantInsert true=作为插入目标（找空槽/可堆叠）；false=作为提取源（找非空）
         */
        private fun resolveSlot(container: Container, fixed: Int, wantInsert: Boolean): Int {
            if (fixed >= 0 && fixed < container.containerSize) {
                return fixed
            }
            for (i in 0 until container.containerSize) {
                val stack = container.getItem(i)
                if (wantInsert && stack.isEmpty) {
                    return i
                }
                if (!wantInsert && !stack.isEmpty) {
                    return i
                }
            }
            return -1
        }
    }
}
