package com.oyxdsg.smartmaid.entity.ai.structure
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.state.BlockState

class TreeStructure : StructureType {
    companion object { const val ID = "tree"; private val LOGS = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("minecraft","logs")); private val LEAVES = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("minecraft","leaves")); private val SPECIES_LEAVES = linkedMapOf("oak" to "oak_leaves","spruce" to "spruce_leaves","birch" to "birch_leaves","jungle" to "jungle_leaves","acacia" to "acacia_leaves","dark_oak" to "dark_oak_leaves","mangrove" to "mangrove_leaves","cherry" to "cherry_leaves","pale_oak" to "pale_oak_leaves")
        @JvmStatic fun leavesIdOf(species: String?): Identifier? = SPECIES_LEAVES[species]?.let { Identifier.fromNamespaceAndPath("minecraft",it) }
    }
    override fun id()=ID
    override fun coreBlock()=java.util.function.Predicate<BlockState>{ it.`is`(LOGS) }
    override fun supportBlock()=java.util.function.Predicate<BlockState>{ it.`is`(LEAVES) }
    override fun isStructure(comp: StructureComponent)=comp.coreCount()>=1 && comp.supportCount()>=1
}
