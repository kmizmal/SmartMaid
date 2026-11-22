package com.oyxdsg.smartmaid.entity.ai.structure
object StructureRegistry {
    private val TYPES = linkedMapOf<String, StructureType>()
    init { register(TreeStructure()); register(OreStructure()) }
    @JvmStatic fun register(type: StructureType?) { if(type?.id()!=null) TYPES[type.id()] = type }
    @JvmStatic fun get(id: String?): StructureType? = id?.let { TYPES[it] }
    @JvmStatic fun getOrTree(id: String?): StructureType = get(id) ?: TYPES["tree"]!!
    @JvmStatic fun exists(id: String?) = get(id)!=null
}
