package com.oyxdsg.smartmaid.entity.ai

import com.oyxdsg.smartmaid.compat.MaidCompat
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import kotlin.math.min

/**
 * 挖掘执行器：接近目标（导航/挖挡路块/搭高）→ 走到旁 → 挥动 + 裂纹 → 耗时至破坏。
 * 26.2 混编迁自 MaidBlockBreaker.java，逻辑逐行对齐。
 */
class MaidBlockBreaker {

    /** 真正目标（矿/要挖的方块）；挖挡路块时与 [target] 不同 */
    private var goal: BlockPos? = null

    /** 当前实际在挖的方块（= goal 或挡路块） */
    private var target: BlockPos? = null
    private var digTicks: Int = 0
    private var digTotal: Int = 0

    /** 直线接近模式（寻路降级用）：不再用原版 A* 导航，直接朝目标走 */
    private var moveStraight: Boolean = false

    // pillar（搭高）状态
    private var pillarJumped: Boolean = false
    private var pillarCell: BlockPos? = null
    private var pillarTicks: Int = 0

    /** 起跳前脚底 y（落地高度检测基准） */
    private var pillarStartY: Double = 0.0

    /** 本次搭高连续失败次数（放块失败重试，超限放弃；每上一层成功清零） */
    private var pillarAttempts: Int = 0

    fun setMoveStraight(moveStraight: Boolean) {
        this.moveStraight = moveStraight
    }

    fun isActive(): Boolean = this.target != null

    fun target(): BlockPos? = this.target

    /**
     * 寻路降级是否刚刚放弃过（[MaidStraightNav.recentlyGaveUp]）。
     *
     * 含义："直线过去也过不去"—— 挡路的挖不动、或需要搭方块但没有建材。
     * 此时再重试导航没有意义，应当放弃当前目标、让调用方换一个。
     */
    private fun navRecentlyGaveUp(maid: SmartMaidEntity): Boolean {
        val nav = maid.navigation
        return nav is MaidGroundPathNavigation && nav.straightNav.recentlyGaveUp(maid.tickCount)
    }

    /** 开始挖 pos；返回 false = 方块已空 / 不可破坏（computeMiningTicks < 0） */
    fun begin(maid: SmartMaidEntity, pos: BlockPos): Boolean {
        if (maid.level().isClientSide) {
            return false
        }
        val state = maid.level().getBlockState(pos)
        if (state.isAir) {
            return false
        }
        this.goal = pos.immutable()
        return beginDig(maid, pos)
    }

    /** 开始挖具体方块（goal/target 已就位） */
    private fun beginDig(maid: SmartMaidEntity, pos: BlockPos): Boolean {
        val state = maid.level().getBlockState(pos)
        if (state.isAir) {
            return false
        }
        // 挖掘前自动换最优工具（对齐玩家判定：正确工具才有掉落、耗时更短）
        MaidActions.equipBestToolFor(maid, state)
        val total = MaidActions.computeMiningTicks(maid, pos)
        if (total < 0) {
            return false
        }
        this.target = pos.immutable()
        this.digTicks = 0
        this.digTotal = total
        MaidCompat.swing(maid, InteractionHand.MAIN_HAND)
        MaidDebug.log("Breaker begin " + this.target + " (" + total + "t)")
        return true
    }

    /**
     * 每服务端 tick 驱动：接近目标（导航/挖挡路块/搭高）→ 走到旁 → 挥动 + 裂纹 → 耗时至破坏。
     *
     * @return true = 真正目标（goal）完成破坏（breaker 已复位）
     */
    fun tick(maid: SmartMaidEntity): Boolean {
        val target = this.target ?: return false
        if (maid.level().isClientSide) {
            return false
        }

        // 挖掘进行中（digTicks > 0）→ 持续挖完当前目标，不被 pillar/挡路块/导航打断：
        //   搭高后站在柱子上挖矿，若每 tick 重新决策，可能因"够不着/命中柱子"被反复打断。
        if (this.digTicks > 0) {
            // 该方块已被挖掉：结束当前方块的破坏流程；如果它是挡路方块，则切回原目标
            if (maid.level().getBlockState(target).isAir) {
                if (this.goal!! != target) {
                    if (maid.level().getBlockState(this.goal!!).isAir) {
                        this.clear()
                        return true
                    }
                    if (beginDig(maid, this.goal!!)) {
                        return false
                    }
                    this.clear()
                    return true
                }
                this.clear()
                return true
            }
            // 继续推进挖掘
            maid.lookControl.setLookAt(target.x + 0.5, target.y + 0.5, target.z + 0.5)
            this.digTicks++
            if (this.digTicks % SWING_INTERVAL == 0) {
                MaidCompat.swing(maid, InteractionHand.MAIN_HAND)
            }
            if (MaidDebug.verbose() && this.digTicks % 10 == 0) {
                MaidDebug.log("Breaker 挖掘中 " + target + " " + this.digTicks + "/" + this.digTotal)
            }
            if (this.digTicks >= this.digTotal) {
                MaidActions.showMiningProgress(maid, target, -1)
                val ok = MaidActions.breakBlock(maid, target)
                // 若刚挖的是挡路块 → 保持已挖空状态，下一 tick 切回真正目标
                if (this.goal!! != target) {
                    return false
                }
                this.clear()
                return ok
            }
            val stage = this.digTicks * 10 / this.digTotal
            MaidActions.showMiningProgress(maid, target, min(9, stage))
            return false
        }

        // 1) 当前目标（可能是挡路块）已被挖掉
        if (maid.level().getBlockState(target).isAir) {
            // 若挖的是挡路块 → 切回真正目标
            if (this.goal!! != target) {
                if (maid.level().getBlockState(this.goal!!).isAir) {
                    this.clear()
                    return true
                }
                if (beginDig(maid, this.goal!!)) {
                    return false
                }
                this.clear()
                return true
            }
            this.clear()
            return true
        }

        // 2) 目标在女仆上方且垂直够不着 → 需要 pillar（最高优先级，先于挖挡路块）
        if (needPillar(maid, target)) {
            // 2a) 水平还远 → 先导航到矿附近
            if (!isHorizReach(maid, target)) {
                if (this.moveStraight) {
                    maid.moveControl.setWantedPosition(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.0)
                } else if (maid.navigation.isDone || maid.tickCount % 20 == 0) {
                    // 导航不可达（同分支 3）→ 放弃该目标，避免死磕
                    if (navRecentlyGaveUp(maid)) {
                        MaidDebug.log("Breaker 导航不可达（降级放弃），放弃目标 " + target)
                        this.clear()
                        return false
                    }
                    MaidActions.navigateTo(maid, target, 1.0)
                }
                return false
            }
            // 2b) 水平已够近 → 原地 pillar 搭高
            if (pillar(maid)) {
                return false
            }
            // pillar 失败（无方块/超时）→ 放弃
            MaidDebug.log("Breaker 无法搭高到 " + target + "，放弃")
            this.clear()
            return false
        }

        // 3) 水平太远 → 导航接近（原版 A* 或寻路降级挖墙/搭高）
        if (!isHorizReach(maid, target)) {
            if (this.moveStraight) {
                maid.moveControl.setWantedPosition(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.0)
            } else if (maid.navigation.isDone || maid.tickCount % 20 == 0) {
                if (navRecentlyGaveUp(maid)) {
                    MaidDebug.log("Breaker 导航不可达（降级放弃），放弃目标 " + target)
                    this.clear()
                    return false
                }
                MaidActions.navigateTo(maid, target, 1.0)
            }
            return false
        }

        // 4) 视线被非目标方块挡住 → 临时挖挡路块（对齐玩家：准星命中哪个挖哪个）
        val hit = MaidActions.lineHit(maid, target)
        if (hit != null && hit != target) {
            if (beginDig(maid, hit)) {
                MaidDebug.log("Breaker 挖挡路块 " + hit + " (目标 " + target + ")")
                return false
            }
            MaidDebug.log("Breaker 挡路块不可挖 " + hit + "，放弃目标 " + target)
            this.clear()
            return false
        }

        // 5) 能直接挖 → 停导航，面向目标，推进挖掘
        maid.navigation.stop()
        maid.lookControl.setLookAt(target.x + 0.5, target.y + 0.5, target.z + 0.5)
        this.digTicks++
        if (this.digTicks % SWING_INTERVAL == 0) {
            MaidCompat.swing(maid, InteractionHand.MAIN_HAND)
        }
        if (MaidDebug.verbose() && this.digTicks % 10 == 0) {
            MaidDebug.log("Breaker 挖掘中 " + target + " " + this.digTicks + "/" + this.digTotal)
        }
        if (this.digTicks >= this.digTotal) {
            MaidActions.showMiningProgress(maid, target, -1)
            val ok = MaidActions.breakBlock(maid, target)
            // 若刚挖的是挡路块 → 不清空 target：保持"已挖空"状态，下一 tick 第 1 步检测 isAir 自动切回
            if (this.goal!! != target) {
                return false
            }
            this.clear()
            return ok
        }
        val stage = this.digTicks * 10 / this.digTotal
        MaidActions.showMiningProgress(maid, target, min(9, stage))
        return false
    }

    /** 水平是否够得着（3 格） */
    private fun isHorizReach(maid: SmartMaidEntity, pos: BlockPos): Boolean {
        val dx = maid.x - (pos.x + 0.5)
        val dz = maid.z - (pos.z + 0.5)
        return dx * dx + dz * dz <= 9.0
    }

    /** 是否需要 pillar 搭高：目标在正上方且垂直超出可挖范围（目标在下方不算） */
    private fun needPillar(maid: SmartMaidEntity, pos: BlockPos): Boolean {
        if (pos.y <= maid.blockPosition().y) {
            return false // 不在上方
        }
        val dy = (pos.y + 0.5) - (maid.y + 0.9)
        return dy > VERT_HI
    }

    /**
     * Pillar 原地搭高：起跳后往脚下放方块，落地站上新方块高度 +1。循环（由 tick 逐层驱动）。
     *
     * @return true = pillar 进行中/本层完成；false = 失败（无方块/超时）
     */
    private fun pillar(maid: SmartMaidEntity): Boolean {
        // 0) 确保主手有可垫方块（先普通建材，没有就用任意方块）
        if (!MaidActions.isBridgeBlock(maid.mainHandItem)) {
            if (!MaidActions.equipBridgeBlockFromBackpack(maid) &&
                !MaidActions.equipBlockFromBackpack(maid)
            ) {
                MaidDebug.log("Breaker pillar 无方块")
                return false
            }
        }
        // 1) 未起跳 → 起跳
        if (!this.pillarJumped) {
            maid.navigation.stop()
            maid.moveControl.setWait()
            this.pillarJumped = true
            this.pillarCell = maid.blockPosition().immutable()
            this.pillarTicks = 0
            this.pillarStartY = maid.y
            maid.jumpControl.jump()
            MaidDebug.log("Breaker pillar 起跳 @" + this.pillarCell + " y=" + this.pillarStartY +
                " 尝试=" + this.pillarAttempts)
            return true
        }

        // 持续锁定水平位置（防起跳后漂移掉下 1 格宽的柱子）
        maid.navigation.stop()
        maid.moveControl.setWait()

        this.pillarTicks++
        // 2) 脚底离开原格（>1.0，原格完全空出无实体碰撞）→ 放方块到脚下
        if (maid.y - this.pillarStartY > 1.0 && maid.level().getBlockState(this.pillarCell!!).isAir) {
            val placed = MaidActions.placeBlock(maid, this.pillarCell!!.below(), Direction.UP)
            MaidDebug.log("Breaker pillar 垫 @" + this.pillarCell + " placed=" + placed + " y=" + maid.y)
        }
        // 3) 落地：脚底比起跳前升高 ≥0.5 格 → 站上新方块，本层完成
        if (maid.onGround() && maid.y - this.pillarStartY >= 0.5) {
            MaidDebug.log("Breaker pillar 上一层 -> y=" + maid.y + " (原 " + this.pillarStartY + ")")
            this.pillarJumped = false
            this.pillarCell = null
            this.pillarAttempts = 0
            return true
        }
        // 4) 落地但没升高（放块失败）→ 重试起跳；连续失败超限才放弃
        if (maid.onGround() && this.pillarTicks > 3) {
            this.pillarJumped = false
            this.pillarAttempts++
            if (this.pillarAttempts >= 8) {
                MaidDebug.log("Breaker pillar 连续失败放弃 @" + this.pillarCell +
                    " y=" + maid.y + " 原=" + this.pillarStartY)
                this.pillarCell = null
                return false
            }
            MaidDebug.log("Breaker pillar 重试塔层 @" + this.pillarCell + " 尝试=" + this.pillarAttempts)
            return true
        }
        // 5) 空中硬超时（异常情况：一直不落地）
        if (this.pillarTicks > PILLAR_TIMEOUT) {
            MaidDebug.log("Breaker pillar 空中超时 @" + this.pillarCell +
                " y=" + maid.y + " 原=" + this.pillarStartY + " onGround=" + maid.onGround())
            this.pillarJumped = false
            this.pillarCell = null
            return false
        }
        return true
    }

    /** 中断：清理裂纹并复位 */
    fun abort(maid: SmartMaidEntity?) {
        val target = this.target
        if (target != null && maid != null && !maid.level().isClientSide) {
            MaidActions.showMiningProgress(maid, target, -1)
        }
        this.clear()
    }

    private fun clear() {
        this.goal = null
        this.target = null
        this.digTicks = 0
        this.digTotal = 0
        this.pillarJumped = false
        this.pillarCell = null
        this.pillarAttempts = 0
    }

    companion object {
        /** 挖掘挥动周期（tick）：按固定间隔播放完整挥动动画，模拟玩家持续挖掘 */
        private const val SWING_INTERVAL = 5
        private const val PILLAR_TIMEOUT = 60

        /** 垂直可挖范围上限（相对女仆身体中心）：头顶两格 */
        private const val VERT_HI = 2.5
    }
}
