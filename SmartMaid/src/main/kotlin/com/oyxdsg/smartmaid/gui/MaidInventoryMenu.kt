package com.oyxdsg.smartmaid.gui

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.init.ModMenus
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * 女仆背包菜单：槽位布局照搬原版 [InventoryMenu]（玩家背包界面）。
 * 女仆背包 41 格，索引与原版玩家背包一致：0-8 热键 / 9-35 背包 / 36-39 盔甲 / 40 副手。
 */
open class MaidInventoryMenu private constructor(
    syncId: Int,
    inv: Inventory,
    maid: SmartMaidEntity?,
    maidInventory: SimpleContainer,
    private val maidData: ContainerData
) : AbstractContainerMenu(ModMenus.MAID_INVENTORY_MENU, syncId) {

    init {
        // 盔甲 4 格（照搬原版坐标：x=8, y=8/26/44/62；index 与 syncSlot 一致：36=HEAD/37=CHEST/38=LEGS/39=FEET/40=OFFHAND）
        this.addSlot(MaidArmorSlot(maid, maidInventory, EquipmentSlot.HEAD, 36, 8, 8, InventoryMenu.EMPTY_ARMOR_SLOT_HELMET))
        this.addSlot(MaidArmorSlot(maid, maidInventory, EquipmentSlot.CHEST, 37, 8, 26, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE))
        this.addSlot(MaidArmorSlot(maid, maidInventory, EquipmentSlot.LEGS, 38, 8, 44, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS))
        this.addSlot(MaidArmorSlot(maid, maidInventory, EquipmentSlot.FEET, 39, 8, 62, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS))
        // 副手（照搬原版坐标：x=77, y=62）
        this.addSlot(MaidArmorSlot(maid, maidInventory, EquipmentSlot.OFFHAND, 40, 77, 62, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD))

        // 女仆背包 27 格（照搬原版 addStandardInventorySlots：y=84/102/120）
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                this.addSlot(Slot(maidInventory, 9 + col + row * 9, 8 + col * 18, 84 + row * 18))
            }
        }
        // 女仆热键 9 格（y=142）
        for (col in 0 until 9) {
            this.addSlot(Slot(maidInventory, col, 8 + col * 18, 142))
        }

        this.addDataSlots(maidData)
    }

    /** 客户端构造：实体引用为 null，容器为本地同步副本 */
    constructor(syncId: Int, inv: Inventory) : this(
        syncId, inv, null, SimpleContainer(41), SimpleContainerData(DATA_COUNT)
    )

    /** 服务端构造：实体引用有效，数据实时读取 */
    constructor(syncId: Int, inv: Inventory, maid: SmartMaidEntity) : this(
        syncId, inv, maid, maid.getMaidInventory(),
        object : ContainerData {
            override fun get(index: Int): Int = when (index) {
                DATA_SIT -> if (maid.isOrderedToSit) 1 else 0
                DATA_HEALTH -> maid.health.toInt()
                DATA_HAS_TARGET -> if (maid.target != null) 1 else 0
                DATA_MAID_ID -> maid.id
                else -> 0
            }

            override fun set(index: Int, value: Int) {
            }

            override fun getCount(): Int = DATA_COUNT
        }
    )

    companion object {
        const val DATA_SIT = 0
        const val DATA_HEALTH = 1
        const val DATA_HAS_TARGET = 2
        /** 女仆实体 id（客户端据此渲染缩小模型预览） */
        const val DATA_MAID_ID = 3
        const val DATA_COUNT = 4

        const val ARMOR_COUNT = 4
        const val INVENTORY_START = 5
    }

    open fun getMaidId(): Int = this.maidData.get(DATA_MAID_ID)

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot: Slot = this.slots[index]
        if (!slot.hasItem()) {
            return ItemStack.EMPTY
        }
        val stack: ItemStack = slot.item
        val original: ItemStack = stack.copy()
        if (index < INVENTORY_START) {
            // 盔甲/副手 → 女仆背包
            if (!this.moveItemStackTo(stack, INVENTORY_START, this.slots.size, true)) {
                return ItemStack.EMPTY
            }
        } else {
            // 女仆背包/热键 → 盔甲（若可装备）
            var moved = false
            for (i in 0 until ARMOR_COUNT) {
                if (this.slots[i].mayPlace(stack)) {
                    if (this.moveItemStackTo(stack, i, i + 1, false)) {
                        moved = true
                        break
                    }
                }
            }
            if (!moved) {
                // 背包 ↔ 热键
                if (index >= 32) {
                    this.moveItemStackTo(stack, INVENTORY_START, 32, false)
                } else {
                    this.moveItemStackTo(stack, 32, this.slots.size, false)
                }
            }
        }
        if (stack.isEmpty) {
            slot.setByPlayer(ItemStack.EMPTY, original)
        } else {
            slot.setChanged()
        }
        if (stack.count == original.count) {
            return ItemStack.EMPTY
        }
        slot.onTake(player, stack)
        return original
    }

    override fun stillValid(player: Player): Boolean = true
}
