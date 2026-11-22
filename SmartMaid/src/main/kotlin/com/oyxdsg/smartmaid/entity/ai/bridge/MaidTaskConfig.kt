package com.oyxdsg.smartmaid.entity.ai.bridge

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.UUID

/** 女仆任务配置持久化。 */
object MaidTaskConfig {
    private val LEGACY_DIR: Path = FabricLoader.getInstance().configDir.resolve("smartmaid").resolve("maids")
    private val GSON = GsonBuilder().setPrettyPrinting().create()

    @JvmStatic
    fun save(maid: SmartMaidEntity, cmd: String, params: JsonObject) {
        val owner = ownerOf(maid) ?: return
        val config = readAll(maid, owner)
        config[cmd] = params
        write(maid, owner, config)
    }

    @JvmStatic
    fun get(maid: SmartMaidEntity, cmd: String): JsonObject? {
        val owner = ownerOf(maid) ?: return null
        return readAll(maid, owner)[cmd]
    }

    @JvmStatic
    fun all(maid: SmartMaidEntity): Map<String, JsonObject> {
        val owner = ownerOf(maid) ?: return LinkedHashMap()
        return readAll(maid, owner)
    }

    @JvmStatic
    fun clear(maid: SmartMaidEntity) {
        val owner = ownerOf(maid) ?: return
        try {
            Files.deleteIfExists(fileFor(maid, owner))
            Files.deleteIfExists(LEGACY_DIR.resolve("$owner.cfg"))
        } catch (e: Exception) {
            SmartMaid.LOGGER.error("清除任务配置失败", e)
        }
    }

    private fun readAll(maid: SmartMaidEntity, owner: UUID): MutableMap<String, JsonObject> {
        val file = fileFor(maid, owner)
        if (!Files.exists(file)) return LinkedHashMap()
        return try {
            val root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).asJsonObject
            LinkedHashMap<String, JsonObject>().apply {
                for ((key, value) in root.entrySet()) if (value.isJsonObject) put(key, value.asJsonObject)
            }
        } catch (e: Exception) {
            SmartMaid.LOGGER.error("读取任务配置失败", e)
            LinkedHashMap()
        }
    }

    private fun write(maid: SmartMaidEntity, owner: UUID, config: Map<String, JsonObject>) {
        try {
            val file = fileFor(maid, owner)
            Files.createDirectories(file.parent)
            val root = JsonObject()
            for ((key, value) in config) root.add(key, value)
            Files.write(file, GSON.toJson(root).toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            SmartMaid.LOGGER.error("保存任务配置失败", e)
        }
    }

    private fun ownerOf(maid: SmartMaidEntity): UUID? = maid.ownerReference?.uuid

    private fun fileFor(maid: SmartMaidEntity, owner: UUID): Path {
        val modern = dirFor(maid).resolve("$owner.cfg")
        if (Files.exists(modern)) return modern
        val legacy = LEGACY_DIR.resolve("$owner.cfg")
        if (!Files.exists(legacy)) return modern
        return try {
            Files.createDirectories(modern.parent)
            Files.copy(legacy, modern, StandardCopyOption.COPY_ATTRIBUTES)
            SmartMaid.LOGGER.info("女仆任务配置已迁移到存档目录: {}", modern)
            modern
        } catch (e: Exception) {
            SmartMaid.LOGGER.warn("女仆任务配置迁移失败，继续使用旧路径: {}", e.toString())
            legacy
        }
    }

    private fun dirFor(maid: SmartMaidEntity): Path {
        val server: MinecraftServer? = maid.level().server
        return server?.getWorldPath(LevelResource.ROOT)?.resolve("smartmaid")?.resolve("maids") ?: LEGACY_DIR
    }
}
