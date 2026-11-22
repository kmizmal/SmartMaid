package com.oyxdsg.smartmaid.client.gui

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** GUI 安全发包：不在游戏中时静默丢弃，绝不抛异常。 */
object MaidNet {
    @JvmStatic
    fun send(payload: CustomPacketPayload): Boolean {
        val mc: Minecraft? = Minecraft.getInstance()
        if (mc == null || mc.connection == null) return false
        ClientPlayNetworking.send(payload)
        return true
    }

    /** 当前是否处于有连接、能发包的状态。 */
    @JvmStatic
    fun canSend(): Boolean {
        val mc: Minecraft? = Minecraft.getInstance()
        return mc != null && mc.connection != null
    }
}
