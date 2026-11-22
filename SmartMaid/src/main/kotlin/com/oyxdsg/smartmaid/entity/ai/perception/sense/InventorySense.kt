package com.oyxdsg.smartmaid.entity.ai.perception.sense

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidActions
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionUtil
import com.oyxdsg.smartmaid.entity.ai.perception.Sense
import net.minecraft.tags.ItemTags
import net.minecraft.tags.TagKey
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

open class InventorySense : Sense {
    override fun intervalTicks() = 20
    override fun sectionName() = "inventory"
    override fun collect(maid: SmartMaidEntity, data: MaidPerception) {
        val inv = maid.maidInventory; val inventory = data.inventory(); val slots = JsonArray()
        for (i in 0 until inv.containerSize) { val stack = inv.getItem(i); if (!stack.isEmpty) { val slot = JsonObject(); slot.addProperty("i", i); PerceptionUtil.writeItem(slot, stack, true); slots.add(slot) } }
        inventory.add("slots", slots); var free = 0; for (i in 0 until inv.containerSize) if (inv.getItem(i).isEmpty) free++; inventory.addProperty("free_slots", free)
        val cap = JsonObject(); cap.addProperty("has_sword", hasTag(inv, ItemTags.SWORDS)); cap.addProperty("has_pickaxe", hasTag(inv, ItemTags.PICKAXES)); cap.addProperty("has_axe", hasTag(inv, ItemTags.AXES)); cap.addProperty("has_shovel", hasTag(inv, ItemTags.SHOVELS)); cap.addProperty("has_hoe", hasTag(inv, ItemTags.HOES)); cap.addProperty("has_food", findFirst(inv, MaidActions::isFood) != null); cap.addProperty("has_block", findFirst(inv) { it.item is BlockItem } != null); cap.addProperty("has_torch", findFirst(inv, ::isTorch) != null); cap.addProperty("food_count", countFood(inv)); val tools = JsonArray(); bestTool(tools, "sword", inv, ItemTags.SWORDS); bestTool(tools, "pickaxe", inv, ItemTags.PICKAXES); bestTool(tools, "axe", inv, ItemTags.AXES); bestTool(tools, "shovel", inv, ItemTags.SHOVELS); bestTool(tools, "hoe", inv, ItemTags.HOES); cap.add("tools", tools); inventory.add("capabilities", cap)
    }
    private fun hasTag(inv: SimpleContainer, tag: TagKey<Item>): Boolean = (0 until inv.containerSize).any { !inv.getItem(it).isEmpty && inv.getItem(it).`is`(tag) }
    private fun findFirst(inv: SimpleContainer, predicate: (ItemStack) -> Boolean): ItemStack? = (0 until inv.containerSize).map { inv.getItem(it) }.firstOrNull { !it.isEmpty && predicate(it) }
    private fun countFood(inv: SimpleContainer): Int = (0 until inv.containerSize).sumOf { val s = inv.getItem(it); if (!s.isEmpty && MaidActions.isFood(s)) s.count else 0 }
    private fun isTorch(s: ItemStack) = s.`is`(Items.TORCH) || s.`is`(Items.SOUL_TORCH) || s.`is`(Items.LANTERN) || s.`is`(Items.SOUL_LANTERN) || s.`is`(Items.REDSTONE_TORCH)
    private fun bestTool(tools: JsonArray, type: String, inv: SimpleContainer, tag: TagKey<Item>) { var best: ItemStack? = null; var remaining = Int.MIN_VALUE; for (i in 0 until inv.containerSize) { val s = inv.getItem(i); if (s.isEmpty || !s.`is`(tag)) continue; val r = s.maxDamage - s.damageValue; if (best == null || r > remaining) { best = s; remaining = r } }; best?.let { val o = JsonObject(); o.addProperty("type", type); o.addProperty("id", PerceptionUtil.itemId(it)); o.addProperty("remaining", remaining); o.addProperty("max_damage", it.maxDamage); tools.add(o) } }
}
