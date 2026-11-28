package com.oyxdsg.smartmaid.gui

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.init.ModMenus
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

/**
 * 女仆管理菜单：展示女仆状态（坐姿/生命/目标），无物品槽位。
 * 状态经 ContainerData 同步客户端；按钮操作通过网络包执行。
 */
open class MaidControlMenu private constructor(
    syncId: Int,
    inv: Inventory,
    private val maidData: ContainerData
) : AbstractContainerMenu(ModMenus.MAID_CONTROL_MENU, syncId) {

    init {
        this.addDataSlots(maidData)
    }

    /** 客户端构造：实体引用为 null */
    constructor(syncId: Int, inv: Inventory) : this(syncId, inv, SimpleContainerData(DATA_COUNT))

    /** 服务端构造：实体引用有效，数据实时读取 */
    constructor(syncId: Int, inv: Inventory, maid: SmartMaidEntity) : this(
        syncId, inv,
        object : ContainerData {
            override fun get(index: Int): Int = when (index) {
                DATA_SIT -> if (maid.isOrderedToSit) 1 else 0
                DATA_HEALTH -> maid.health.toInt()
                DATA_HAS_TARGET -> if (maid.target != null) 1 else 0
                DATA_FRIENDLY_FIRE -> if (maid.getSettings().isFriendlyFire()) 1 else 0
                DATA_FOLLOW_ENABLED -> if (maid.getSettings().isFollowEnabled()) 1 else 0
                DATA_FOLLOW_START -> maid.getSettings().followStartIndex
                DATA_FOLLOW_STOP -> maid.getSettings().followStopIndex
                DATA_PROTECT_MODE -> maid.getSettings().protectMode.ordinal
                DATA_DEATH_DROP -> maid.getSettings().deathDropMode.ordinal
                DATA_MAX_HEALTH -> maid.getSettings().maxHealthIndex
                DATA_HUNGER_RATE -> maid.getSettings().hungerRateIndex
                DATA_REGEN_RATE -> maid.getSettings().regenRateIndex
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
        // 女仆设置（档位索引，客户端按索引显示文案；修改走 MaidSettingsPayload）
        const val DATA_FRIENDLY_FIRE = 3
        const val DATA_FOLLOW_ENABLED = 4
        const val DATA_FOLLOW_START = 5
        const val DATA_FOLLOW_STOP = 6
        const val DATA_PROTECT_MODE = 7
        const val DATA_DEATH_DROP = 8
        const val DATA_MAX_HEALTH = 9
        const val DATA_HUNGER_RATE = 10
        const val DATA_REGEN_RATE = 11
        const val DATA_COUNT = 12
    }

    open fun getSitState(): Int = this.maidData.get(DATA_SIT)

    open fun getHealthInt(): Int = this.maidData.get(DATA_HEALTH)

    /** 通用设置读取：客户端 UI 按 dataIndex 取当前档位索引 */
    open fun getSetting(dataIndex: Int): Int = this.maidData.get(dataIndex)

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean = true
}
