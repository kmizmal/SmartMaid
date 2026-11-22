package com.oyxdsg.smartmaid.entity.ai.structure
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.function.Predicate
class OreStructure : StructureType {
    companion object { const val ID = "ore"; private val ORE_TAGS = arrayOf("coal_ores","copper_ores","iron_ores","gold_ores","redstone_ores","lapis_ores","diamond_ores","emerald_ores","quartz_ores")
        @JvmStatic fun isOreProduce(produce: String?) = produce?.contains("_ores") == true }
    override fun id() = ID
    override fun coreBlock(): Predicate<BlockState> = Predicate { state -> ORE_TAGS.any { state.`is`(TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("minecraft", it))) } }
    override fun supportBlock() = Predicate<BlockState> { false }
    override fun isStructure(comp: StructureComponent) = comp.coreCount() >= 1
}
