package com.oyxdsg.smartmaid.network

import com.oyxdsg.smartmaid.SmartMaid
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/** 服务端 → 客户端：打开女仆主菜单。 */
@JvmRecord
data class MaidOpenMenuPayload(val maidId: Int, val food: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MaidOpenMenuPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<MaidOpenMenuPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "open_maid_menu"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, MaidOpenMenuPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MaidOpenMenuPayload::maidId,
            ByteBufCodecs.VAR_INT, MaidOpenMenuPayload::food,
            ::MaidOpenMenuPayload
        )
    }
}
