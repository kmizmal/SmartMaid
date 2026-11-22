package com.oyxdsg.smartmaid.network

import com.oyxdsg.smartmaid.SmartMaid
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/** 女仆设置修改包（客户端 → 服务端）。 */
@JvmRecord
data class MaidSettingsPayload(val field: String, val value: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MaidSettingsPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<MaidSettingsPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_settings"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, MaidSettingsPayload> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MaidSettingsPayload::field,
            ByteBufCodecs.VAR_INT, MaidSettingsPayload::value,
            ::MaidSettingsPayload
        )
    }
}
