package com.oyxdsg.smartmaid.entity.ai

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.Level

/**
 * 女仆搭方块执行器：把「确保主手持方块 → 走到目标格旁 → 自动选支撑面放置方块」抽成独立状态机，
 * 供搭路/垫脚/爬高以及寻路降级复用。
 *
 * 公开方法名与 Java 原版逐字一致（setMoveStraight/setBridgeMode/isActive/target/begin/tick/abort），
 * 因为 Kotlin 侧 MaidStraightNav.kt 与 BuildTask.kt 也以方法语法调用这些 API。
 */
open class MaidBlockPlacer {

    private var target: BlockPos? = null

    /** 直线接近模式（寻路降级用）：不再用原版 A* 导航，直接朝目标走。 */
    private var moveStraight: Boolean = false

    /** 搭路模式（寻路降级用）：只消耗普通建材，不浪费贵重方块。 */
    private var bridgeMode: Boolean = false

    open fun setMoveStraight(moveStraight: Boolean) {
        this.moveStraight = moveStraight
    }

    open fun setBridgeMode(bridgeMode: Boolean) {
        this.bridgeMode = bridgeMode
    }

    /** 是否正在搭方块。 */
    open fun isActive(): Boolean = this.target != null

    open fun target(): BlockPos? = this.target

    /** 开始往 pos 格搭方块；返回 false = 背包没有可放置方块。 */
    open fun begin(maid: SmartMaidEntity, pos: BlockPos): Boolean {
        if (maid.level().isClientSide) return false
        if (this.bridgeMode) {
            if (!MaidActions.hasBridgeBlock(maid)) {
                MaidDebug.log("Placer 搭路失败：背包没有可搭路的普通方块")
                return false
            }
        } else if (!MaidActions.hasBlockItem(maid)) {
            return false
        }
        // 预检：目标格必须能找到可点击的支撑面，否则搭不了（避免无限失败刷屏卡死）
        if (findClickFace(maid, pos) == null) {
            MaidDebug.log("Placer 无支撑面，拒绝 " + pos)
            return false
        }
        this.target = pos.immutable()
        MaidDebug.log("Placer begin " + this.target)
        return true
    }

    /**
     * 每服务端 tick 驱动：确保主手持方块 → 走到目标旁 → 选支撑面放置。
     * @return true = target 格已放上方块（target 已清空）
     */
    open fun tick(maid: SmartMaidEntity): Boolean {
        val t = this.target ?: return false
        if (maid.level().isClientSide) return false
        // 目标格已有方块 → 本轮完成
        if (!maid.level().getBlockState(t).isAir) {
            clear()
            return true
        }
        // 确保主手是合适的方块物品（搭路模式只换普通建材，贵重方块也换掉）
        val handOk = if (this.bridgeMode) {
            MaidActions.isBridgeBlock(maid.getMainHandItem())
        } else {
            maid.getMainHandItem().getItem() is BlockItem
        }
        if (!handOk) {
            val equipped = if (this.bridgeMode) {
                MaidActions.equipBridgeBlockFromBackpack(maid)
            } else {
                MaidActions.equipBlockFromBackpack(maid)
            }
            if (!equipped) {
                clear()
                return false
            }
        }
        // 先走到目标格旁
        if (!MaidActions.isWithinReach(maid, t, 3.0)) {
            if (this.moveStraight) {
                maid.moveControl.setWantedPosition(
                    t.x.toDouble() + 0.5, t.y.toDouble(), t.z.toDouble() + 0.5, 1.0
                )
            } else if (maid.navigation.isDone || maid.tickCount % 20 == 0) {
                MaidActions.navigateTo(maid, t, 1.0)
            }
            return false
        }
        maid.navigation.stop()
        // 选支撑面并放置
        val face = findClickFace(maid, t)
        if (face == null) {
            // 找不到可点击支撑面（目标四周全悬空）→ 中止本次搭方块，避免无限重试
            MaidDebug.log("Placer 无支撑面，中止 " + t)
            clear()
            return false
        }
        val clickPos = t.relative(face.opposite)
        if (MaidActions.placeBlock(maid, clickPos, face)) {
            clear()
            return true
        }
        return false
    }

    open fun abort() {
        clear()
    }

    /**
     * 找 target 格放置时可点击的支撑面：
     * 优先下方（UP，搭桥替换水/岩浆）；下方悬空则找水平相邻实心格的朝向 target 的面。
     */
    private fun findClickFace(maid: SmartMaidEntity, pos: BlockPos): Direction? {
        val level: Level = maid.level()
        val below = pos.below()
        if (below.y >= level.minY && !level.getBlockState(below).isAir) {
            return Direction.UP
        }
        for (dir in Direction.Plane.HORIZONTAL) {
            val neighbor = pos.relative(dir)
            if (!level.getBlockState(neighbor).isAir) {
                return dir.opposite
            }
        }
        return null
    }

    private fun clear() {
        this.target = null
    }
}
