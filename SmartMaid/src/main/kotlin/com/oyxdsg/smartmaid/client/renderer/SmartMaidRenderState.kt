package com.oyxdsg.smartmaid.client.renderer

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.client.renderer.entity.state.HumanoidRenderState
import net.minecraft.network.chat.Component

open class SmartMaidRenderState : HumanoidRenderState() {
    @JvmField var sitting = false
    @JvmField var bubbleLines: List<Component> = emptyList()
    @JvmField var debugAnim = 0
    @JvmField var tickDelta = 0f
    @JvmField var maid: SmartMaidEntity? = null
}
