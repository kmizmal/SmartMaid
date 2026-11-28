package com.oyxdsg.smartmaid.network

import com.oyxdsg.smartmaid.SmartMaid
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

@JvmRecord
data class MaidMenuActionPayload(
    val op: String,
    val id: String,
    val kind: String,
    val value: Int,
    val cmd: String,
    val params: String
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<MaidMenuActionPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<MaidMenuActionPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_menu_action"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, MaidMenuActionPayload> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MaidMenuActionPayload::op,
            ByteBufCodecs.STRING_UTF8, MaidMenuActionPayload::id,
            ByteBufCodecs.STRING_UTF8, MaidMenuActionPayload::kind,
            ByteBufCodecs.VAR_INT, MaidMenuActionPayload::value,
            ByteBufCodecs.STRING_UTF8, MaidMenuActionPayload::cmd,
            ByteBufCodecs.STRING_UTF8, MaidMenuActionPayload::params,
            ::MaidMenuActionPayload
        )

        @JvmStatic
        fun of(op: String) = MaidMenuActionPayload(op, "", "", 0, "", "")

        @JvmStatic
        fun op(op: String, id: String, kind: String) = MaidMenuActionPayload(op, id, kind, 0, "", "")

        @JvmStatic
        fun enqueue(cmd: String, paramsJson: String) = MaidMenuActionPayload("enqueue", "", "", 0, cmd, paramsJson)
    }
}
