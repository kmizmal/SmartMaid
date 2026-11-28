package com.oyxdsg.smartmaid

import com.oyxdsg.smartmaid.command.MaidAICommand
import com.oyxdsg.smartmaid.command.MaidAnimCommand
import com.oyxdsg.smartmaid.command.MaidPerceptionCommand
import com.oyxdsg.smartmaid.command.MaidTaskCommand
import com.oyxdsg.smartmaid.command.SummonMaidCommand
import com.oyxdsg.smartmaid.data.SmartMaidConfig
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidAutoTest
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidWsClient
import com.oyxdsg.smartmaid.entity.ai.craft.CraftExecutor
import com.oyxdsg.smartmaid.init.ModEntities
import com.oyxdsg.smartmaid.init.ModMenus
import com.oyxdsg.smartmaid.network.ModNetworking
import com.oyxdsg.smartmaid.test.MaidErrorSink
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.commands.Commands
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Default Fabric adapter creates this class through its public no-argument constructor. */
open class SmartMaid : ModInitializer {
    override fun onInitialize() {
        MaidErrorSink.setFallbackLogger { message, error -> LOGGER.warn(message, error) }
        MaidErrorSink.install("server")
        ModEntities.register()
        ModMenus.register()
        ModNetworking.register()
        CommandRegistrationCallback.EVENT.register { dispatcher, _, selection ->
            val common = SmartMaidConfig.commandPermission(selection)
            val admin = Commands.LEVEL_GAMEMASTERS
            SummonMaidCommand.register(dispatcher, common)
            MaidTaskCommand.register(dispatcher, common)
            MaidAICommand.register(dispatcher, admin)
            MaidAnimCommand.register(dispatcher, admin)
            MaidPerceptionCommand.register(dispatcher, admin)
        }
        ServerTickEvents.END_SERVER_TICK.register(MaidAutoTest::onServerTick)
        ServerLifecycleEvents.SERVER_STARTED.register { CraftExecutor.invalidateCache() }
        ServerLifecycleEvents.SERVER_STARTED.register(MaidWsClient::onServerStarted)
        ServerLifecycleEvents.SERVER_STOPPING.register(MaidWsClient::onServerStopping)
        ServerTickEvents.END_SERVER_TICK.register(MaidWsClient::onServerTick)
        MaidDebug.reload()
        LOGGER.info("Smart Maid initialized (debug={}, verbose={})", MaidDebug.enabled(), MaidDebug.verbose())
    }

    companion object {
        const val MOD_ID: String = "smartmaid"

        // Keep the public static field used by the existing Java sources and compatibility layers.
        @JvmField
        val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
    }
}
