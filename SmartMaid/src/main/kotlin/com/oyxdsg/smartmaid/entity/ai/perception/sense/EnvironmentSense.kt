package com.oyxdsg.smartmaid.entity.ai.perception.sense

import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception
import com.oyxdsg.smartmaid.entity.ai.perception.Sense
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap

class EnvironmentSense : Sense {
    override fun intervalTicks(): Int = 40
    override fun sectionName(): String = "env"

    override fun collect(maid: SmartMaidEntity, data: MaidPerception) {
        val level: Level = maid.level()
        val env: JsonObject = data.env()
        val pos: BlockPos = maid.blockPosition()
        env.addProperty("biome", level.getBiome(pos).unwrapKey().map { it.identifier().toString() }.orElse("?"))
        val dayTime = Math.floorMod(level.overworldClockTime, 24000L)
        env.addProperty("time_of_day", dayTime)
        env.addProperty("is_day", dayTime < 13000L)
        env.addProperty("weather", if (level.isThundering) "thunder" else if (level.isRaining) "rain" else "clear")
        env.addProperty("rain_level", Math.round(level.getRainLevel(1.0f) * 100.0f) / 100.0f)
        env.addProperty("thunder_level", Math.round(level.getThunderLevel(1.0f) * 100.0f) / 100.0f)
        env.addProperty("light_level", level.getMaxLocalRawBrightness(pos))
        env.addProperty("height", level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.x, pos.z))
        env.addProperty("min_y", level.minY)
        env.addProperty("max_y", level.maxY)
        val cp: ChunkPos = level.getChunkAt(pos).pos
        env.add("chunk", JsonObject().apply {
            addProperty("cx", cp.x)
            addProperty("cz", cp.z)
            addProperty("loaded", level.hasChunkAt(pos))
        })
        if (level is ServerLevel) env.addProperty("chunks_loaded", level.chunkSource.loadedChunksCount)
    }
}
