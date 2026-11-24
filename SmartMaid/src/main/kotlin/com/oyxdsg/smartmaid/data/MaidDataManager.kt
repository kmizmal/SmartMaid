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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

object MaidDataManager {
    private val LEGACY_DIR = FabricLoader.getInstance().configDir.resolve("smartmaid").resolve("maids")

    @JvmStatic fun save(maid: SmartMaidEntity) {
        if (maid.level().isClientSide) return
        val owner = maid.ownerReference?.uuid ?: return
        try {
            val ops = RegistryOps.create(NbtOps.INSTANCE, maid.registryAccess())
            val tag = CompoundTag(); val list = ListTag()
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
                ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result().ifPresent { slotTag.put("item", it) }; invList.add(slotTag)
            }
            tag.put("inventory", invList)
            val tasksTag = CompoundTag(); maid.maidTaskManager.saveToTag(tasksTag); tag.put("tasks", tasksTag)
            val file = fileFor(maid, owner); Files.createDirectories(file.parent); NbtIo.writeCompressed(tag, file)
        } catch (e: Exception) { SmartMaid.LOGGER.error("保存女仆数据失败", e) }
    }

    @JvmStatic fun load(maid: SmartMaidEntity) {
        if (maid.level().isClientSide) return
        val owner = maid.ownerReference?.uuid ?: return
        val file = fileFor(maid, owner); if (!Files.exists(file)) return
        try {
            val tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            val ops = RegistryOps.create(NbtOps.INSTANCE, maid.registryAccess())
            val list = tag.getListOrEmpty("equipment")
            for (i in list.indices) {
                val slotTag = list.getCompoundOrEmpty(i); val slot = runCatching { EquipmentSlot.byName(slotTag.getStringOr("slot", "").lowercase()) }.getOrNull(); val itemTag = slotTag.get("item") ?: continue
                if (slot == null) continue
                ItemStack.OPTIONAL_CODEC.parse(ops, itemTag).result().ifPresent { maid.setItemSlot(slot, it) }
            }
            val invList = tag.getListOrEmpty("inventory")
            for (i in invList.indices) {
                val slotTag = invList.getCompoundOrEmpty(i); val itemTag = slotTag.get("item") ?: continue
                val idx = slotTag.getIntOr("slot", -1); if (idx !in 0 until maid.maidInventory.containerSize) continue
                val stack = ItemStack.OPTIONAL_CODEC.parse(ops, itemTag).result().orElse(ItemStack.EMPTY); if (stack.isEmpty) continue
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
        } catch (e: Exception) { SmartMaid.LOGGER.error("读取女仆数据失败", e) }
    }

    @JvmStatic fun delete(maid: SmartMaidEntity) {
        val owner = maid.ownerReference?.uuid ?: return
        try { Files.deleteIfExists(fileFor(maid, owner)); Files.deleteIfExists(LEGACY_DIR.resolve("$owner.dat")) }
        catch (e: Exception) { SmartMaid.LOGGER.error("删除女仆存档失败", e) }
    }

    private fun fileFor(maid: SmartMaidEntity, owner: UUID): java.nio.file.Path {
        val modern = dirFor(maid).resolve("$owner.dat"); if (Files.exists(modern)) return modern
        val legacy = LEGACY_DIR.resolve("$owner.dat"); if (!Files.exists(legacy)) return modern
        return try { Files.createDirectories(modern.parent); Files.copy(legacy, modern, StandardCopyOption.COPY_ATTRIBUTES); SmartMaid.LOGGER.info("女仆数据已迁移到存档目录: {}", modern); modern }
        catch (e: Exception) { SmartMaid.LOGGER.warn("女仆数据迁移失败，继续使用旧路径: {}", e.toString()); legacy }
    }
    private fun dirFor(maid: SmartMaidEntity): java.nio.file.Path { val server: MinecraftServer? = maid.level().server; return server?.getWorldPath(LevelResource.ROOT)?.resolve("smartmaid")?.resolve("maids") ?: LEGACY_DIR }
    private fun armorSlotForIndex(index: Int): EquipmentSlot? = when (index) { 36 -> EquipmentSlot.HEAD; 37 -> EquipmentSlot.CHEST; 38 -> EquipmentSlot.LEGS; 39 -> EquipmentSlot.FEET; 40 -> EquipmentSlot.OFFHAND; else -> null }
}
