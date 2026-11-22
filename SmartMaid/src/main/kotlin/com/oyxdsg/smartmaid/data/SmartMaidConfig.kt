package com.oyxdsg.smartmaid.data

import com.google.gson.*
import com.oyxdsg.smartmaid.SmartMaid
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.Commands
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.permissions.PermissionCheck
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.block.Block
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object SmartMaidConfig {
    private val GSON = GsonBuilder().setPrettyPrinting().create()
    private const val FILE_NAME = "smartmaid/main.json"
    private var loaded = false
    private var commandPermission = "auto"
    private var debug = false
    private var debugVerbose = false
    private val bridgeBlocksBlacklist = HashSet<Block>()
    private val combatNeverTarget = HashSet<EntityType<*>>()
    private var craftWhitelistMode = "off"
    private var autoTidyWhenFull = true

    @JvmStatic @Synchronized fun load() {
        if (loaded) return
        loaded = true
        val file = FabricLoader.getInstance().configDir.resolve(FILE_NAME)
        val cfg = if (Files.isRegularFile(file)) {
            try { JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).asJsonObject }
            catch (e: Exception) { SmartMaid.LOGGER.warn("main.json 解析失败，使用默认配置: {}", e.toString()); defaults() }
        } else defaults().also { writeDefault(file, it) }
        apply(cfg)
    }
    private fun defaults() = JsonObject().apply {
        addProperty("commandPermission", "auto"); addProperty("debug", false); addProperty("debugVerbose", false)
        add("bridgeBlocksBlacklist", JsonArray()); add("combatNeverTarget", JsonArray()); addProperty("craftWhitelistMode", "off"); addProperty("autoTidyWhenFull", true)
    }
    private fun writeDefault(file: java.nio.file.Path, cfg: JsonObject) { try { Files.createDirectories(file.parent); Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8); SmartMaid.LOGGER.info("已生成默认配置: {}", file) } catch (e: Exception) { SmartMaid.LOGGER.warn("main.json 写入失败: {}", e.toString()) } }
    private fun apply(cfg: JsonObject) {
        if (cfg.has("commandPermission") && cfg["commandPermission"].isJsonPrimitive) commandPermission = cfg["commandPermission"].asString
        if (cfg.has("debug") && cfg["debug"].isJsonPrimitive) debug = cfg["debug"].asBoolean
        if (cfg.has("debugVerbose") && cfg["debugVerbose"].isJsonPrimitive) debugVerbose = cfg["debugVerbose"].asBoolean
        bridgeBlocksBlacklist.clear(); readIds(cfg, "bridgeBlocksBlacklist").forEach { id -> BuiltInRegistries.BLOCK.getOptional(id).ifPresentOrElse({ bridgeBlocksBlacklist.add(it) }, { SmartMaid.LOGGER.warn("bridgeBlocksBlacklist 未知方块: {}", id) }) }
        combatNeverTarget.clear(); readIds(cfg, "combatNeverTarget").forEach { id -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresentOrElse({ combatNeverTarget.add(it) }, { SmartMaid.LOGGER.warn("combatNeverTarget 未知实体: {}", id) }) }
        if (cfg.has("craftWhitelistMode") && cfg["craftWhitelistMode"].isJsonPrimitive) craftWhitelistMode = cfg["craftWhitelistMode"].asString.lowercase()
        if (cfg.has("autoTidyWhenFull") && cfg["autoTidyWhenFull"].isJsonPrimitive) autoTidyWhenFull = cfg["autoTidyWhenFull"].asBoolean
    }
    private fun readIds(cfg: JsonObject, key: String): Set<Identifier> = buildSet {
        if (!cfg.has(key) || !cfg[key].isJsonArray) return@buildSet
        cfg.getAsJsonArray(key).forEach { el -> if (el.isJsonPrimitive) Identifier.tryParse(el.asString.trim())?.let { add(it) } ?: SmartMaid.LOGGER.warn("{} 中的 id 非法: {}", key, el.asString) }
    }
    @JvmStatic fun commandPermission(selection: Commands.CommandSelection): PermissionCheck { load(); return when (commandPermission) { "all" -> Commands.LEVEL_ALL; "moderators" -> Commands.LEVEL_MODERATORS; "gamemasters" -> Commands.LEVEL_GAMEMASTERS; else -> if (selection == Commands.CommandSelection.INTEGRATED) Commands.LEVEL_ALL else Commands.LEVEL_GAMEMASTERS } }
    @JvmStatic fun bridgeBlocksBlacklist(): Set<Block> { load(); return bridgeBlocksBlacklist }
    @JvmStatic fun combatNeverTarget(): Set<EntityType<*>> { load(); return combatNeverTarget }
    @JvmStatic fun craftWhitelistMode(): String { load(); return craftWhitelistMode }
    @JvmStatic fun autoTidyWhenFull(): Boolean { load(); return autoTidyWhenFull }
    @JvmStatic fun debug(): Boolean { load(); return debug }
    @JvmStatic fun debugVerbose(): Boolean { load(); return debugVerbose }
}
