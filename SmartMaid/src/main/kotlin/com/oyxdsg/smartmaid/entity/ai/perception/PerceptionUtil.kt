package com.oyxdsg.smartmaid.entity.ai.perception

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.FluidTags
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

object PerceptionUtil {
    @JvmStatic fun pos(o: JsonObject, v: Vec3) { val a = JsonArray(); a.add(Math.round(v.x * 10.0) / 10.0); a.add(Math.round(v.y * 10.0) / 10.0); a.add(Math.round(v.z * 10.0) / 10.0); o.add("pos", a) }
    @JvmStatic fun blockPos(o: JsonObject, p: BlockPos) { val a = JsonArray(); a.add(p.x); a.add(p.y); a.add(p.z); o.add("pos", a) }
    @JvmStatic fun itemId(stack: ItemStack): String = BuiltInRegistries.ITEM.getKey(stack.item).toString()
    @JvmStatic fun writeItem(o: JsonObject, stack: ItemStack, full: Boolean) {
        if (stack.isEmpty) return
        o.addProperty("id", itemId(stack)); o.addProperty("count", stack.count)
        if (full) {
            o.addProperty("max_stack", stack.maxStackSize)
            if (stack.isDamaged) { o.addProperty("damage", stack.damageValue); o.addProperty("max_damage", stack.maxDamage) }
            val ench: ItemEnchantments = stack.enchantments
            if (!ench.isEmpty) { val list = JsonArray(); ench.keySet().forEach { h -> val e = JsonObject(); e.addProperty("id", h.unwrapKey().map { k -> k.identifier().toString() }.orElse("?")); e.addProperty("level", ench.getLevel(h)); list.add(e) }; o.add("enchants", list) }
            stack.get(DataComponents.CUSTOM_NAME)?.let { o.addProperty("name", it.string) }
        }
    }
    @JvmStatic fun writeEffects(list: JsonArray, effects: Collection<MobEffectInstance>) { effects.forEach { effect -> val e = JsonObject(); e.addProperty("id", effect.effect.unwrapKey().map { k -> k.identifier().toString() }.orElse("?")); e.addProperty("amp", effect.amplifier); e.addProperty("duration", effect.duration); list.add(e) } }
    @JvmStatic fun classifyBlock(level: Level, pos: BlockPos): String {
        val state = level.getBlockState(pos)
        if (state.isAir) return "air"
        if (state.fluidState.`is`(FluidTags.WATER)) return "water"
        if (state.fluidState.`is`(FluidTags.LAVA) || state.block == Blocks.FIRE) return "lava"
        val shape = state.getCollisionShape(level, pos)
        if (shape.isEmpty) return "empty"
        val maxY = shape.max(Direction.Axis.Y)
        return when { maxY >= 0.95 -> "solid"; maxY >= 0.4 -> "half"; else -> "low" }
    }
    @JvmStatic fun blockId(state: BlockState): String = BuiltInRegistries.BLOCK.getKey(state.block).toString()
}
