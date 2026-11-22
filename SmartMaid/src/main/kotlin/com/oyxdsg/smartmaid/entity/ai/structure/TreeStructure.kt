package com.oyxdsg.smartmaid.entity.ai.structure
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
class TreeStructure : StructureType {
    companion object { const val ID = "tree"; private val LOGS = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("minecraft","logs")); private val LEAVES = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("minecraft","leaves")); private val SPECIES_LEAVES = linkedMapOf("oak" to "oak_leaves","spruce" to "spruce_leaves","birch" to "birch_leaves","jungle" to "jungle_leaves","acacia" to "acacia_leaves","dark_oak" to "dark_oak_leaves","mangrove" to "mangrove_leaves","cherry" to "cherry_leaves","pale_oak" to "pale_oak_leaves")
        @JvmStatic fun isLog(state: BlockState)=state.`is`(LOGS); @JvmStatic fun isLeaves(state: BlockState)=state.`is`(LEAVES)
        @JvmStatic fun speciesOfLeaves(state: BlockState): String? { val id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block) ?: return null; val p=id.path; return if(p.endsWith("_leaves")) p.removeSuffix("_leaves") else null }
        @JvmStatic fun leavesIdOf(species: String?): Identifier? = SPECIES_LEAVES[species]?.let { Identifier.fromNamespaceAndPath("minecraft",it) }
    }
    override fun id()=ID
    override fun coreBlock()=java.util.function.Predicate<BlockState>{ it.`is`(LOGS) }
    override fun supportBlock()=java.util.function.Predicate<BlockState>{ it.`is`(LEAVES) }
    override fun isStructure(comp: StructureComponent)=comp.coreCount()>=1 && comp.supportCount()>=1
}
