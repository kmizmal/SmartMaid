package com.oyxdsg.smartmaid.init

import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.gui.MaidControlMenu
import com.oyxdsg.smartmaid.gui.MaidInventoryMenu
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.MenuType

/** 女仆 GUI 菜单注册：背包 + 管理。 */
object ModMenus {
    lateinit var MAID_INVENTORY_MENU: MenuType<MaidInventoryMenu>

    lateinit var MAID_CONTROL_MENU: MenuType<MaidControlMenu>

    @JvmStatic
    fun register() {
        MAID_INVENTORY_MENU = Registry.register(
            BuiltInRegistries.MENU,
            ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_inventory")),
            MenuType({ syncId, inventory -> MaidInventoryMenu(syncId, inventory) }, FeatureFlags.VANILLA_SET)
        )
        MAID_CONTROL_MENU = Registry.register(
            BuiltInRegistries.MENU,
            ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_control")),
            MenuType({ syncId, inventory -> MaidControlMenu(syncId, inventory) }, FeatureFlags.VANILLA_SET)
        )
    }
}
