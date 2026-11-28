package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import net.minecraft.core.BlockPos

/** JSON position helpers; static methods retain the Java call sites' existing API. */
class TaskState private constructor() {
    companion object {
        @JvmStatic
        fun pos(position: BlockPos): JsonArray = JsonArray().apply {
            add(position.x)
            add(position.y)
            add(position.z)
        }

        @JvmStatic
        fun readPos(element: JsonElement?): BlockPos? {
            if (element == null || !element.isJsonArray || element.asJsonArray.size() < 3) return null
            val array = element.asJsonArray
            return BlockPos(array[0].asInt, array[1].asInt, array[2].asInt)
        }

        @JvmStatic
        fun posList(positions: Collection<BlockPos>): JsonArray = JsonArray().apply {
            for (position in positions) add(pos(position))
        }

        @JvmStatic
        fun readPosList(element: JsonElement?): MutableList<BlockPos> {
            val positions = ArrayList<BlockPos>()
            if (element != null && element.isJsonArray) {
                for (entry in element.asJsonArray) readPos(entry)?.let(positions::add)
            }
            return positions
        }
    }
}
