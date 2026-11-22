package com.oyxdsg.smartmaid.entity.ai.structure
import net.minecraft.world.level.block.state.BlockState
import java.util.function.Predicate
interface StructureType {
    fun id(): String
    fun coreBlock(): Predicate<BlockState>
    fun supportBlock(): Predicate<BlockState>
    fun isStructure(comp: StructureComponent): Boolean
    fun scanBlockLimit() = 20_000
}
