package com.oyxdsg.smartmaid.init

import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory

object ModEntities {
    lateinit var SMART_MAID: EntityType<SmartMaidEntity>

    @JvmStatic
    fun register() {
        val id = Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "smart_maid")
        val key: ResourceKey<EntityType<*>> = ResourceKey.create(
            Registries.ENTITY_TYPE,
            id
        )
        SMART_MAID = Registry.register<EntityType<*>, EntityType<SmartMaidEntity>>(
            BuiltInRegistries.ENTITY_TYPE,
            key,
            EntityType.Builder.of<SmartMaidEntity>({ type, level -> SmartMaidEntity(type, level) }, MobCategory.CREATURE)
                .sized(0.6f, 1.8f)
                .noSave()
                .build(key)
        )
        FabricDefaultAttributeRegistry.register(SMART_MAID, SmartMaidEntity.createAttributes())
    }
}
