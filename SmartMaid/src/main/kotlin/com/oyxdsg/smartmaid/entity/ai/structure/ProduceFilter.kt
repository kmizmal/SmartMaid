package com.oyxdsg.smartmaid.entity.ai.structure

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.*
import java.util.function.Predicate

/** 产物过滤：把 produce 参数编译成 BlockState 谓词，并推导物种。 */
object ProduceFilter {
    @JvmRecord
    data class Compiled(val matcher: Predicate<BlockState>?, val species: String?)

    private val WOOD_SUFFIXES = arrayOf("_log", "_wood")

    @JvmStatic
    fun compile(produce: String?): Compiled {
        if (produce == null || produce.trim().isEmpty()) return Compiled({ true }, null)
        val p = produce.trim()

        if (p.startsWith("#")) {
            val tagId = Identifier.tryParse(p.substring(1)) ?: return Compiled({ false }, null)
            val tag: TagKey<Block> = TagKey.create(Registries.BLOCK, tagId)
            val species = speciesFromPath(stripLogsSuffix(tagId.path))
            return Compiled({ state -> state.`is`(tag) }, species)
        }

        if (!p.contains(':')) {
            val wood = p.lowercase(Locale.ROOT)
            val ids = woodFamilyIds(wood)
            return Compiled({ state ->
                val bid = BuiltInRegistries.BLOCK.getKey(state.block)
                bid != null && bid.namespace == "minecraft" && ids.contains(bid.path)
            }, wood)
        }

        val id = Identifier.tryParse(p) ?: return Compiled({ false }, null)
        val block = BuiltInRegistries.BLOCK.get(id).map { it.value() }.orElse(null)
        val species = speciesFromPath(id.path)
        return Compiled({ state -> block != null && state.block === block }, species)
    }

    private fun woodFamilyIds(wood: String): Set<String> = buildSet {
        for (suffix in WOOD_SUFFIXES) {
            add(wood + suffix)
            add("stripped_" + wood + suffix)
        }
    }

    private fun speciesFromPath(path: String?): String? {
        if (path == null) return null
        var p = path
        if (p.startsWith("stripped_")) p = p.removePrefix("stripped_")
        for (suffix in WOOD_SUFFIXES) {
            if (p.endsWith(suffix)) return p.removeSuffix(suffix)
        }
        if (p.endsWith("_leaves")) return p.removeSuffix("_leaves")
        return p
    }

    private fun stripLogsSuffix(path: String?): String? =
        if (path != null && path.endsWith("_logs")) path.removeSuffix("_logs") else path
}
