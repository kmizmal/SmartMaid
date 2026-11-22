package com.oyxdsg.smartmaid.network

import com.oyxdsg.smartmaid.SmartMaid
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/** 玩家在聊天栏对女仆说的话（客户端 → 服务端）。 */
@JvmRecord
data class MaidChatPayload(val text: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MaidChatPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<MaidChatPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_chat"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, MaidChatPayload> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MaidChatPayload::text,
            ::MaidChatPayload
        )
    }
}
