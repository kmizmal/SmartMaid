package com.oyxdsg.smartmaid.network

import com.oyxdsg.smartmaid.SmartMaid
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/** 女仆 GUI 动作包（客户端 → 服务端）。 */
@JvmRecord
data class MaidCommandPayload(val action: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MaidCommandPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<MaidCommandPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_command"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, MaidCommandPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MaidCommandPayload::action,
            ::MaidCommandPayload
        )

        @JvmField
        val ACTION_TOGGLE_SIT = 0
        @JvmField
        val ACTION_RECALL = 1
        @JvmField
        val ACTION_CLEAR_TARGET = 2
        @JvmField
        val ACTION_OPEN_INVENTORY = 3
    }
}
