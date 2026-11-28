package com.oyxdsg.smartmaid.network

import com.oyxdsg.smartmaid.SmartMaid
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/** 服务端 → 客户端：女仆菜单状态快照。 */
@JvmRecord
data class MaidMenuStatePayload(val json: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MaidMenuStatePayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<MaidMenuStatePayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_menu_state"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, MaidMenuStatePayload> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MaidMenuStatePayload::json,
            ::MaidMenuStatePayload
        )
    }
}
