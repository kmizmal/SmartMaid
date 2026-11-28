package com.oyxdsg.smartmaid.entity.ai.structure
import net.minecraft.core.BlockPos
open class StructureComponent(private val cores: List<BlockPos>, private val supports: List<BlockPos>) {
    open fun cores(): List<BlockPos> = cores
    open fun supports(): List<BlockPos> = supports
    open fun coreCount() = cores.size
    open fun supportCount() = supports.size
    open fun copy() = StructureComponent(ArrayList(cores), ArrayList(supports))
}
