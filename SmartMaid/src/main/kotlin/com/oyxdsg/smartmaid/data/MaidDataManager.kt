package com.oyxdsg.smartmaid.data

import com.mojang.serialization.DataResult
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidAIBridge
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.nbt.*
import net.minecraft.resources.RegistryOps
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.LevelResource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

object MaidDataManager {
    private val LEGACY_DIR = FabricLoader.getInstance().configDir.resolve("smartmaid").resolve("maids")

    // A chunk can leave the visible entity index before its entities are actually removed.
    // Only the server thread touches this set; weak references do not retain old worlds.
    private val summoned = Collections.newSetFromMap(WeakHashMap<SmartMaidEntity, Boolean>())

    private fun activeOwnedMaids(server: MinecraftServer, owner: UUID): Sequence<SmartMaidEntity> = sequence {
        yieldAll(summoned.asSequence().filter { it.level().server === server })
        for (level in server.allLevels) {
            yieldAll(level.allEntities.asSequence().filterIsInstance<SmartMaidEntity>())
        }
    }.filter { !it.isRemoved && it.ownerReference?.uuid == owner }

    @JvmStatic
    fun findOwnedMaid(server: MinecraftServer, owner: UUID, exclude: SmartMaidEntity? = null): SmartMaidEntity? =
        activeOwnedMaids(server, owner).firstOrNull { it !== exclude }

    @JvmStatic
    fun hasOwnedMaid(server: MinecraftServer, owner: UUID): Boolean = findOwnedMaid(server, owner) != null

    /** Called only after the new entity has been inserted successfully. */
    fun registerSummoned(maid: SmartMaidEntity) {
        val owner = requireNotNull(maid.ownerReference).uuid
        val server = requireNotNull(maid.level().server)
        val dir = dirFor(server)
        val file = MaidDataFiles.dataFile(dir, owner, maid.uuid)
        if (!MaidDataFiles.hasData(file)) writeSnapshot(maid, file)
        // An unreadable predecessor prevents cleanup, not the existing selection/registration step.
        val previous = try {
            Result.success(MaidDataFiles.selectedSnapshotId(dir, owner))
        } catch (e: Exception) {
            Result.failure(e)
        }
        MaidDataFiles.selectSnapshot(dir, owner, maid.uuid)
        summoned.add(maid)

        // Retention is best effort after commit: a cleanup failure must not discard the new maid.
        try {
            val previousId = previous.getOrThrow()
            val protected = activeOwnedMaids(server, owner).map { it.uuid }.toSet()
            for (failed in MaidDataFiles.pruneSupersededSnapshots(dir, owner, maid.uuid, previousId, protected)) {
                SmartMaid.LOGGER.warn("无法清理旧女仆快照 {}", failed)
            }
        } catch (e: Exception) {
            SmartMaid.LOGGER.warn("跳过玩家 {} 的旧女仆快照清理", owner, e)
        }
    }

    @JvmStatic
    fun save(maid: SmartMaidEntity) {
        if (maid.level().isClientSide) return
        val owner = maid.ownerReference?.uuid ?: return
        val server = maid.level().server ?: return
        try {
            writeSnapshot(maid, MaidDataFiles.dataFile(dirFor(server), owner, maid.uuid))
        } catch (e: Exception) {
            SmartMaid.LOGGER.error("保存女仆数据失败", e)
        }
    }

    private fun writeSnapshot(maid: SmartMaidEntity, file: Path) {
        val ops = RegistryOps.create(NbtOps.INSTANCE, maid.registryAccess())
        val tag = CompoundTag();
        val list = ListTag()
        for (slot in EquipmentSlot.VALUES) {
            val stack = maid.getItemBySlot(slot); if (stack.isEmpty) continue
            val slotTag = CompoundTag(); slotTag.putString("slot", slot.getName())
            val encoded: DataResult<Tag> = ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack)
            encoded.result().ifPresent { slotTag.put("item", it) }; list.add(slotTag)
        }
        tag.put("equipment", list)
        val invList = ListTag()
        for (i in 0 until maid.maidInventory.containerSize) {
            val stack = maid.maidInventory.getItem(i); if (stack.isEmpty) continue
            val slotTag = CompoundTag(); slotTag.putInt("slot", i)
            ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result()
                .ifPresent { slotTag.put("item", it) }; invList.add(slotTag)
        }
        tag.put("inventory", invList)
        val tasksTag = CompoundTag(); maid.maidTaskManager.saveToTag(tasksTag); tag.put("tasks", tasksTag)
        MaidDataFiles.replaceFile(file) { NbtIo.writeCompressed(tag, it) }
    }

    @JvmStatic
    fun load(maid: SmartMaidEntity) {
        if (maid.level().isClientSide) return
        val owner = maid.ownerReference?.uuid ?: return
        val dir = dirFor(requireNotNull(maid.level().server))
        val file = try {
            MaidDataFiles.prepareSummon(dir, LEGACY_DIR, owner, maid.uuid)
        } catch (e: IOException) {
            // A copy failure never changes where future writes go.
            val fallback = MaidDataFiles.recoverySource(dir, LEGACY_DIR, owner) ?: throw e
            SmartMaid.LOGGER.warn("女仆存档复制失败，仅读取原存档 {}", fallback, e)
            fallback
        }
        if (Files.notExists(file)) return
        val tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
        val ops = RegistryOps.create(NbtOps.INSTANCE, maid.registryAccess())
        val list = tag.getListOrEmpty("equipment")
        for (i in list.indices) {
            val slotTag = list.getCompoundOrEmpty(i);
            val slot = runCatching { EquipmentSlot.byName(slotTag.getStringOr("slot", "").lowercase()) }.getOrNull();
            val itemTag = slotTag.get("item") ?: continue
            if (slot == null) continue
            ItemStack.OPTIONAL_CODEC.parse(ops, itemTag).result().ifPresent { maid.setItemSlot(slot, it) }
        }
        val invList = tag.getListOrEmpty("inventory")
        for (i in invList.indices) {
            val slotTag = invList.getCompoundOrEmpty(i);
            val itemTag = slotTag.get("item") ?: continue
            val idx = slotTag.getIntOr("slot", -1); if (idx !in 0 until maid.maidInventory.containerSize) continue
            val stack = ItemStack.OPTIONAL_CODEC.parse(ops, itemTag).result()
                .orElse(ItemStack.EMPTY); if (stack.isEmpty) continue
            if (idx >= 36) {
                val es = armorSlotForIndex(idx)
                if (es == null || (idx != 40 && !maid.isEquippableInSlot(stack, es))) {
                    val remaining = maid.moveToBackpack(stack); if (!remaining.isEmpty) continue; continue
                }
            }
            maid.maidInventory.setItem(idx, stack)
        }
        val tasksTag = tag.getCompoundOrEmpty("tasks")
        if (!tasksTag.isEmpty) {
            val restored = maid.maidTaskManager.loadFromTag(tasksTag) { m, cmd, params, err ->
                // TaskFactory 的参数声明为可空（历史 Java 接口无注解），而 buildTask 的契约是非空：
                // 缺任一必需项就无法重建任务，直接返回 null 跳过该项（与原 Java 的空指针路径相比更安全）。
                if (m == null || cmd == null || params == null || err == null) null
                else MaidAIBridge.buildTask(m, cmd, params, err)
            }
            if (restored > 0) SmartMaid.LOGGER.info("女仆任务队列已恢复 {} 项（自动继续）", restored)
        }
    }

    @JvmStatic
    fun delete(maid: SmartMaidEntity) {
        val owner = maid.ownerReference?.uuid ?: return
        val server = maid.level().server ?: return
        try {
            Files.deleteIfExists(MaidDataFiles.dataFile(dirFor(server), owner, maid.uuid))
        } catch (e: Exception) {
            SmartMaid.LOGGER.error("删除女仆存档失败", e)
        }
    }

    private fun dirFor(server: MinecraftServer): Path =
        server.getWorldPath(LevelResource.ROOT).resolve("smartmaid").resolve("maids")

    private fun armorSlotForIndex(index: Int): EquipmentSlot? = when (index) {
        36 -> EquipmentSlot.HEAD; 37 -> EquipmentSlot.CHEST; 38 -> EquipmentSlot.LEGS; 39 -> EquipmentSlot.FEET; 40 -> EquipmentSlot.OFFHAND; else -> null
    }
}
