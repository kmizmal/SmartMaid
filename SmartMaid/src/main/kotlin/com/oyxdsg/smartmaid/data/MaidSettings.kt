package com.oyxdsg.smartmaid.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.world.entity.ai.attributes.Attributes
import java.nio.file.Files
import java.util.UUID

class MaidSettings {
    enum class ProtectMode { ACTIVE, PASSIVE, OFF }
    enum class DeathDropMode { ALL, KEEP_EQUIPMENT, NONE }

    var friendlyFire = false
        private set
    var followEnabled = true
        private set

    @JvmName("isFriendlyFire")
    fun isFriendlyFire(): Boolean = friendlyFire

    @JvmName("isFollowEnabled")
    fun isFollowEnabled(): Boolean = followEnabled
    var followStartIndex = 1
        private set
    var followStopIndex = 1
        private set
    var protectMode = ProtectMode.ACTIVE
        private set
    var deathDropMode = DeathDropMode.ALL
        private set
    var maxHealthIndex = 2
        private set
    var hungerRateIndex = 1
        private set
    var regenRateIndex = 1
        private set

    fun applyField(field: String, value: Int) {
        when (field) {
            "friendlyFire" -> friendlyFire = value != 0
            "followEnabled" -> followEnabled = value != 0
            "followStart" -> followStartIndex = clampIndex(value, FOLLOW_START_STEPS.size)
            "followStop" -> followStopIndex = clampIndex(value, FOLLOW_STOP_STEPS.size)
            "protectMode" -> protectMode = ProtectMode.entries[clampIndex(value, ProtectMode.entries.size)]
            "deathDrop" -> deathDropMode = DeathDropMode.entries[clampIndex(value, DeathDropMode.entries.size)]
            "maxHealth" -> maxHealthIndex = clampIndex(value, MAX_HEALTH_STEPS.size)
            "hungerRate" -> hungerRateIndex = clampIndex(value, RATE_STEPS.size)
            "regenRate" -> regenRateIndex = clampIndex(value, RATE_STEPS.size)
        }
    }

    fun applyToMaid(maid: SmartMaidEntity) {
        maid.getAttribute(Attributes.MAX_HEALTH)?.let { attribute ->
            val max = MAX_HEALTH_STEPS[clampIndex(maxHealthIndex, MAX_HEALTH_STEPS.size)]
            attribute.baseValue = max
            if (maid.health > max) maid.health = max.toFloat()
        }
        maid.maidFood.setRates(RATE_STEPS[clampIndex(hungerRateIndex, RATE_STEPS.size)], RATE_STEPS[clampIndex(regenRateIndex, RATE_STEPS.size)])
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("friendlyFire", if (friendlyFire) 1 else 0)
        addProperty("followEnabled", if (followEnabled) 1 else 0)
        addProperty("followStart", followStartIndex)
        addProperty("followStop", followStopIndex)
        addProperty("protectMode", protectMode.ordinal)
        addProperty("deathDrop", deathDropMode.ordinal)
        addProperty("maxHealth", maxHealthIndex)
        addProperty("hungerRate", hungerRateIndex)
        addProperty("regenRate", regenRateIndex)
    }

    companion object {
        @JvmField val FOLLOW_START_STEPS = floatArrayOf(2f, 3f, 5f, 8f, 12f)
        @JvmField val FOLLOW_STOP_STEPS = floatArrayOf(6f, 10f, 15f, 24f, 40f)
        @JvmField val MAX_HEALTH_STEPS = doubleArrayOf(10.0, 20.0, 24.0, 40.0, 60.0, 100.0)
        @JvmField val RATE_STEPS = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f)
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        private val CACHE = HashMap<UUID, MaidSettings>()
        private val DIR = FabricLoader.getInstance().configDir.resolve("smartmaid").resolve("settings")

        @JvmStatic fun get(owner: UUID?): MaidSettings {
            if (owner == null) return MaidSettings()
            return CACHE[owner] ?: read(owner).also { CACHE[owner] = it }
        }
        @JvmStatic fun save(owner: UUID?) {
            if (owner == null) return
            val settings = CACHE[owner] ?: return
            try {
                Files.createDirectories(DIR)
                Files.newBufferedWriter(fileFor(owner)).use { GSON.toJson(settings, it) }
            } catch (e: Exception) { SmartMaid.LOGGER.error("保存女仆设置失败: {}", owner, e) }
        }
        private fun read(owner: UUID): MaidSettings {
            val file = fileFor(owner)
            if (!Files.exists(file)) return MaidSettings()
            return try { Files.newBufferedReader(file).use { GSON.fromJson(it, MaidSettings::class.java) ?: MaidSettings() } }
            catch (e: Exception) { SmartMaid.LOGGER.error("读取女仆设置失败: {}", owner, e); MaidSettings() }
        }
        private fun fileFor(owner: UUID) = DIR.resolve("$owner.json")
        private fun clampIndex(value: Int, length: Int) = if (value < 0) 0 else minOf(value, length - 1)
    }
}
