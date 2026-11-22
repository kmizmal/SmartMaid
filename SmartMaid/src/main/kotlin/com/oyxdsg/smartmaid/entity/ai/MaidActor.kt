package com.oyxdsg.smartmaid.entity.ai

import com.mojang.authlib.GameProfile
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.fabricmc.fabric.api.entity.FakePlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand

/**
 * 女仆的"动作执行身份"—— 以主人身份运行的 FakePlayer。
 *
 * 整合包里大量 mod 直接访问 player（潜行/UUID 归属/领地权限/统计）。用主人的 GameProfile
 * 让这些判定全部按主人走（女仆是主人的手）；每次取用把 actor 挪到女仆位置，否则 mod 会在
 * (0,0,0) 附近判定。手持同步为同一 ItemStack 引用，mod 对物品的消耗正确反映到女仆手上。
 * 只在服务端逻辑调用（FakePlayer.get 是服务端操作）。
 */
object MaidActor {
    /**
     * 取一个"以主人身份、位于女仆当前位置"的动作执行者。
     * @return 服务端 FakePlayer；不在服务端时返回 null
     */
    @JvmStatic
    fun actorFor(maid: SmartMaidEntity): ServerPlayer? {
        val level = maid.level() as? ServerLevel ?: return null
        val actor = FakePlayer.get(level, ownerProfile(maid))
        // 位置/朝向同步：领地与区域判定依赖坐标
        actor.setPos(maid.getX(), maid.getY(), maid.getZ())
        actor.setYRot(maid.getYRot())
        actor.setXRot(maid.getXRot())
        // 手持同步：第三方方块直接读 player.getItemInHand()
        actor.setItemInHand(InteractionHand.MAIN_HAND, maid.getMainHandItem())
        actor.setItemInHand(InteractionHand.OFF_HAND, maid.getOffhandItem())
        return actor
    }

    /** 优先用主人的身份；主人离线/无主人时退化为女仆自身 UUID 的合成身份。 */
    private fun ownerProfile(maid: SmartMaidEntity): GameProfile {
        val owner = maid.getOwner()
        if (owner is ServerPlayer) {
            return owner.getGameProfile()
        }
        return GameProfile(maid.getUUID(), "SmartMaid")
    }
}
