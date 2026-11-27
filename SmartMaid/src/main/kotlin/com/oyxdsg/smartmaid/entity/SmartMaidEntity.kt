package com.oyxdsg.smartmaid.entity

import com.oyxdsg.smartmaid.SmartMaid

import com.oyxdsg.smartmaid.data.MaidDataManager
import com.oyxdsg.smartmaid.data.MaidSettings
import com.oyxdsg.smartmaid.entity.SmartMaidEntity.Companion.TELEPORT_FALLBACK_DISTANCE_SQR
import com.oyxdsg.smartmaid.entity.ai.*
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidTelemetryWriter
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidWsClient
import com.oyxdsg.smartmaid.entity.ai.combat.MaidCombatGoal
import com.oyxdsg.smartmaid.entity.ai.combat.MaidCombatObservation
import com.oyxdsg.smartmaid.entity.ai.combat.MaidTargetFilter
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidTaskManager
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionModule
import com.oyxdsg.smartmaid.network.MaidOpenMenuPayload
import com.oyxdsg.smartmaid.network.ModNetworking
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.*
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.RandomStrollGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.ai.targeting.TargetingConditions
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.food.FoodProperties
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ProjectileWeaponItem
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ContainerOpenersCounter
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.*
import java.util.function.Predicate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 智能女仆实体（MVP，Minecraft 26.2 Fabric）。
 *
 * 继承 [TamableAnimal] 获得标准驯服/主人/坐下能力，AI 使用轻量 Goal 系统。
 *
 * 分层设计：
 * - **L0 安全层** — [applySafetyRules] 硬编码危险拦截（岩浆/火），不依赖外部输入
 * - **L1 规则层** — Goal 系统表达确定性行为（跟随/散步）
 */
open class SmartMaidEntity(type: EntityType<out TamableAnimal>, level: Level) :
    TamableAnimal(type, level), ContainerUser {

    private val actionExecutorStorage: MaidActionExecutor
    private val maidTaskManagerStorage: MaidTaskManager
    private val perceptionModuleStorage: PerceptionModule

    /** 战斗 Goal：接管索敌后的走位/攻击（战斗高于 AI 任务；注册于 registerGoals） */
    private var combatGoal: MaidCombatGoal? = null

    private val maidFoodStorage: MaidFoodData = MaidFoodData()

    /** 玩家绑定的女仆设置（护主模式/跟随/掉落/属性等；首次取用时按主人 UUID 加载） */
    private var settingsStorage: MaidSettings = MaidSettings()

    /** 已加载设置对应的主人 UUID（主人变化时重新加载） */
    private var settingsOwner: UUID? = null

    /** 女仆背包：41 格，索引照搬原版玩家 Inventory（0-8 热键 / 9-35 背包 / 36-39 盔甲 / 40 副手） */
    private val maidInventoryStorage: SimpleContainer = SimpleContainer(41)

    /** 拾取抑制截止 tick：刚把物品丢给主人后，女仆在这之前不去捡（避免"丢出去又被立刻捡回"） */
    private var pickupSuppressUntil: Int = 0

    /** 聊天气泡剩余 tick（>0 时头顶显示气泡文字，替代默认名字） */
    private var bubbleTicksLeft: Int = 0

    /** 气泡占用 customName 前的名字（供 [baseName] 上报，避免上报气泡文案） */
    private var nameBeforeBubble: String? = null

    /** 移动兜底冷却（tick）：传送后暂停一段时间，避免每 tick 反复尝试 */
    private var teleportFallbackCooldown: Int = 0

    /** 当前"打开"的容器位置（chestopen 指令记录，供 chestput/chesttake 使用） */
    private var openedContainerPos: BlockPos? = null

    /** 最近一次攻击者缓存（B 方案：hurtServer 最早阶段抓取，弥补 getLastHurtByMob 被消费后为 null） */
    private var lastAttacker: Entity? = null

    /** 最近一次受伤的 tick（新鲜度保护：仅 5s 内有效） */
    private var lastAttackerTick: Int = 0

    // ---------------------------------------------------------------------
    // 属性 / 背包等公开访问器
    //
    // Kotlin 侧同时存在两种调用风格（`maid.getX()` 与 `maid.x`），因此这里
    // 为每个属性保留一个显式的 getX() 方法；属性 getter 用 @JvmName 改名，
    // 避免和显式方法产生 JVM 签名冲突。
    // ---------------------------------------------------------------------

    @get:JvmName("getActionExecutorValue")
    val actionExecutor: MaidActionExecutor
        get() = getActionExecutor()

    fun getActionExecutor(): MaidActionExecutor = actionExecutorStorage

    @get:JvmName("getMaidTaskManagerValue")
    val maidTaskManager: MaidTaskManager
        get() = getMaidTaskManager()

    /** AI 任务调度器（指令入口经此设置任务） */
    fun getMaidTaskManager(): MaidTaskManager = maidTaskManagerStorage

    @get:JvmName("getPerceptionModuleValue")
    val perceptionModule: PerceptionModule
        get() = getPerceptionModule()

    /** 感知模块（采样/查询感知快照） */
    fun getPerceptionModule(): PerceptionModule = perceptionModuleStorage

    @get:JvmName("getMaidFoodValue")
    val maidFood: MaidFoodData
        get() = getMaidFood()

    /** 女仆饱食度（内部状态） */
    fun getMaidFood(): MaidFoodData = maidFoodStorage

    @get:JvmName("getSettingsValue")
    val settings: MaidSettings
        get() = getSettings()

    /**
     * 玩家绑定的女仆设置。服务端首次取用时按主人 UUID 从磁盘加载，并即时应用属性。
     * 客户端（无主人归属语义）直接返回当前实例，UI 数值由菜单 ContainerData 提供。
     */
    fun getSettings(): MaidSettings {
        if (this.level().isClientSide) {
            return this.settingsStorage
        }
        val ownerRef = this.ownerReference
        val owner = ownerRef?.uuid
        if (owner != null && owner != this.settingsOwner) {
            this.settingsStorage = MaidSettings.get(owner)
            this.settingsOwner = owner
            this.settingsStorage.applyToMaid(this)
        }
        return this.settingsStorage
    }

    @get:JvmName("getMaidInventoryValue")
    val maidInventory: SimpleContainer
        get() = getMaidInventory()

    /** 女仆背包容器（41 格，索引照搬原版玩家背包） */
    fun getMaidInventory(): SimpleContainer = maidInventoryStorage

    /** AI 任务是否正在接管行为决策（跟随/自动攻击/散步 Goal 据此让路） */
    val isAiBusy: Boolean
        get() = this.maidTaskManagerStorage.isAiBusy()

    /** 是否处于战斗状态（战斗优先于 AI 任务；寻路降级据此禁用） */
    fun isCombatActive(): Boolean = this.combatGoal != null && this.combatGoal!!.isEngaged()

    /** 只读战斗观测（测试用）；无战斗 Goal 时返回 null。 */
    fun combatObservation(): MaidCombatObservation? = combatGoal?.observe()

    /** 最近攻击者（感知/遥测事件用）。缓存新鲜度 100 tick（5s）。 */
    val recentAttacker: Entity?
        get() {
            if (this.lastAttacker == null || this.tickCount - this.lastAttackerTick > 100) {
                return null
            }
            return this.lastAttacker
        }

    // ---------------------------------------------------------------------
    // 构造
    // ---------------------------------------------------------------------

    init {
        this.setPersistenceRequired()
        // 移动控制：沿用原版 MoveControl 体系，跳跃触发参照车万女仆优化
        this.moveControl = MaidMoveControl(this)
        // 动作执行器 / AI 任务调度器 / 感知模块（构造参数均为 this）
        this.actionExecutorStorage = MaidActionExecutor(this)
        this.maidTaskManagerStorage = MaidTaskManager(this)
        this.perceptionModuleStorage = PerceptionModule(this)
        // 拾取地上的掉落物（进女仆背包）
        this.setCanPickUpLoot(true)
        this.customName = DISPLAY_NAME
        this.isCustomNameVisible = true
    }

    // ---------------------------------------------------------------------
    // ContainerUser
    // ---------------------------------------------------------------------

    /** ContainerUser：容器交互距离（开箱动画用，参照玩家默认 5 格） */
    override fun getContainerInteractionRange(): Double = 5.0

    /** ContainerUser：女仆无打开的容器菜单，恒 false（开箱动画由 openCount 驱动） */
    override fun hasContainerOpen(counter: ContainerOpenersCounter, pos: BlockPos): Boolean = false

    /** ContainerUser：返回自身，供开箱动画取实体 */
    override fun getLivingEntity(): LivingEntity = this

    // ---------------------------------------------------------------------
    // 同步数据
    // ---------------------------------------------------------------------

    protected override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(DATA_DEBUG_ANIM, 0)
        builder.define(DATA_BUBBLE, "")
    }

    /** 头顶气泡文本（客户端渲染读取；空串=无气泡） */
    fun getBubbleText(): String = this.entityData.get(DATA_BUBBLE)

    /** 当前强制播放的调试动画 id（0=无，客户端渲染读取） */
    fun getDebugAnim(): Int = this.entityData.get(DATA_DEBUG_ANIM)

    /** 设置强制播放的调试动画 id（服务端，仅开发校验用） */
    fun setDebugAnim(id: Int) {
        this.entityData.set(DATA_DEBUG_ANIM, id)
    }

    // ---------------------------------------------------------------------
    // 坐下
    // ---------------------------------------------------------------------

    override fun setOrderedToSit(sit: Boolean) {
        // 26.2 的 TamableAnimal.setOrderedToSit 只设本地字段，不设坐姿标志（DATA_FLAGS），
        // 导致客户端 isInSittingPose() 一直 false、坐姿渲染不生效。这里补上同步标志。
        super.setOrderedToSit(sit)
        this.setInSittingPose(sit)
        if (this.level().isClientSide) {
            return
        }
        // 服务端：坐姿姿态 + 落到支撑面 + 锁死移动（所有入口——右键 / GUI / 指令 / AI 桥接——统一走这里）
        this.pose = if (sit) Pose.SITTING else Pose.STANDING
        if (sit) {
            this.snapToSupportForSit()
            this.target = null
            this.applySitLock()
        }
    }

    /**
     * 坐下前的"落地"：把女仆吸附到脚下支撑面（地面 / 半砖 / 台阶 / 地毯顶面）。
     *
     * 从脚下所在格向下最多 3 格找最近的可站立碰撞顶面，且顶面不高于当前脚下
     * ——这样坐在**半砖**上时脚下坐标正是半砖顶（方块基座 + 0.5），
     * 渲染层再下沉髋部高度即可让屁股贴住半砖，而不是浮在半空。
     */
    private fun snapToSupportForSit() {
        val level = this.level() as? ServerLevel ?: return
        val feetY = this.y
        for (dy in 0 downTo -3) {
            val pos = this.blockPosition().offset(0, dy, 0)
            val state = level.getBlockState(pos)
            if (state.isAir) {
                continue
            }
            val shape = state.getCollisionShape(level, pos)
            if (shape.isEmpty) {
                continue
            }
            val top = pos.y + shape.max(Direction.Axis.Y)
            if (top > feetY + 0.02) {
                // 支撑面高于脚下（不该发生）：继续往下找，避免把女仆顶上去
                continue
            }
            if (abs(top - feetY) > 0.001) {
                this.setPos(this.x, top, this.z)
                this.setDeltaMovement(0.0, 0.0, 0.0)
                MaidDebug.log(
                    "Maid sit 落地: y " + MaidDebug.fmt1(feetY) + " -> " + MaidDebug.fmt1(top) +
                        " pos=" + this.blockPosition().toShortString()
                )
            }
            return
        }
        // 脚下 3 格内没有支撑面（悬空/深坑）：不强行挪动，交给重力自然落下
        MaidDebug.log("Maid sit 无支撑面，保持原位 pos=" + this.blockPosition().toShortString())
    }

    /**
     * 坐姿锁定：坐下期间不允许任何移动（清移动意图 + 清水平惯性）。
     *
     * 坐下时女仆会因为残余路径 / MoveControl 操作 / 击退 / 水流被推着走
     * （表现为"坐着走"）。这里每 tick 清一次导航与水平速度；只清水平分量，
     * 保留 y 速度让重力把她正常压在地面/半砖上。
     */
    private fun applySitLock() {
        if (!this.isOrderedToSit) {
            return
        }
        if (this.target != null) {
            this.target = null
        }
        this.getNavigation().stop()
        this.getMoveControl().setWait()
        this.setXxa(0.0F)
        this.setZza(0.0F)
        val dx = this.deltaMovement.x
        val dz = this.deltaMovement.z
        if (dx * dx + dz * dz > 1.0E-6) {
            this.setDeltaMovement(0.0, this.deltaMovement.y, 0.0)
        }
    }

    // ---------------------------------------------------------------------
    // 属性 / 战斗 / 交互
    // ---------------------------------------------------------------------

    /**
     * 覆写原版近战距离判定为固定 [MAID_ATTACK_REACH] 格。
     *
     * 原版默认 DEFAULT_ATTACK_REACH = 3.0（或物品 ATTACK_RANGE 组件）；
     * [net.minecraft.world.entity.ai.goal.MeleeAttackGoal.canPerformAttack] 经此方法判断能否出手。
     */
    override fun isWithinMeleeAttackRange(target: LivingEntity): Boolean {
        return this.getAttackBoundingBox(MAID_ATTACK_REACH).intersects(target.boundingBox)
    }

    /**
     * 覆写原版架盾判定：去掉 blockDelayTicks（盾 5 tick）前摇，举盾立即生效。
     *
     * 原版 getItemBlockingWith 要求「使用时长 ≥ 盾的 blockDelayTicks()（0.25s = 5 tick）」
     * 才认作举盾中——那是给"需要按住右键"的玩家设计的平衡（防止瞬发格挡）；
     * 女仆是 PVE 高手、反应极快，命中前检测到威胁即可当场举盾挡下，不受该前摇限制。
     */
    override fun getItemBlockingWith(): ItemStack? {
        if (!this.isUsingItem) {
            return null
        }
        val item = this.getUseItem()
        return if (item.has(DataComponents.BLOCKS_ATTACKS)) item else null
    }

    /** 友军免伤：默认不把玩家作为攻击目标（友军=玩家自己；可在女仆菜单开启"友军伤害"） */
    override fun canAttack(target: LivingEntity): Boolean {
        if (target is Player && !this.getSettings().isFriendlyFire()) {
            return false
        }
        return super.canAttack(target)
    }

    /** 友军免伤硬拦截：未开启友军伤害时，即使被显式指定也绝不伤害玩家 */
    override fun doHurtTarget(level: ServerLevel, target: Entity): Boolean {
        if (target is Player && !this.getSettings().isFriendlyFire()) {
            return false
        }
        return super.doHurtTarget(level, target)
    }

    /**
     * 远程弹药来源（仿玩家 Player.getProjectile）：原版 LivingEntity.getProjectile
     * 默认返回空，只有玩家会从背包找箭。女仆在此从自己背包找武器支持的弹药（箭）。
     */
    override fun getProjectile(weapon: ItemStack): ItemStack {
        if (weapon.item is ProjectileWeaponItem) {
            val weaponItem = weapon.item as ProjectileWeaponItem
            val supported: Predicate<ItemStack> = weaponItem.allSupportedProjectiles
            for (i in 0 until this.maidInventory.containerSize) {
                val stack = this.maidInventory.getItem(i)
                if (supported.test(stack)) {
                    return stack
                }
            }
        }
        return ItemStack.EMPTY
    }

    /**
     * 进食完成钩子：原版 FoodProperties.onConsume 只在 instanceof Player 时加饱食，
     * 女仆需要在这里按 FOOD 组件补记饱食度（食物效果由 Consumable 已先行应用）。
     */
    protected override fun completeUsingItem() {
        val used = this.getUseItem().copy()
        super.completeUsingItem()
        val food: FoodProperties? = used.get(DataComponents.FOOD)
        if (food != null) {
            this.maidFood.eat(food)
            MaidDebug.log(
                "Maid eat " + used.item.getDescriptionId() +
                    " food=" + this.maidFood.getFoodLevel() +
                    " sat=" + MaidDebug.fmt1(this.maidFood.getSaturationLevel().toDouble())
            )
        }
    }

    /** 当前"打开"的容器位置（chestopen 设置；chestput/chesttake 读取） */
    fun getOpenedContainer(): BlockPos? = this.openedContainerPos

    fun setOpenedContainer(pos: BlockPos?) {
        this.openedContainerPos = pos
    }

    /**
     * 受伤入口（26.2 签名：hurtServer(ServerLevel, DamageSource, float)）。
     *
     * 在最早期阶段缓存攻击者：DamageSource 的 causing/direct 实体在构造时即固定，
     * 不受 LivingEntity 内部 lastHurtBy* 重置 / EntityReference 失效影响
     * ——解决感知事件 hurt 攻击者常为 ? 的问题。
     */
    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        this.captureAttacker(source)
        val wasBlocking = this.isBlocking
        val result = super.hurtServer(level, source, amount)
        // 中箭诊断：打印箭矢实体 / 女仆举盾状态（朝向 + 时间）/ 格挡情况，排查"打骷髅总是中箭"
        val direct = source.directEntity
        if (direct is AbstractArrow) {
            val ap: Vec3 = direct.position()
            val av: Vec3 = direct.deltaMovement
            val owner: Entity? = direct.getOwner()
            // 箭矢来向 vs 女仆朝向的水平夹角（0=正对，盾牌格挡在 ~100° 内有效）
            val dx = ap.x - this.x
            val dz = ap.z - this.z
            val arrowYawDeg = Math.toDegrees(Mth.atan2(-dx, dz))
            val facingErr = Mth.wrapDegrees(this.getYHeadRot() - arrowYawDeg.toFloat())
            val shieldSlot = this.offhandItem
            MaidDebug.log(
                "Combat hit-by-arrow arrow=" + direct.type.toShortString() +
                    " owner=" + (owner?.getType()?.toShortString() ?: "?") +
                    " pos=(" + ap.x + "," + ap.y + "," + ap.z + ") vel=(" +
                    String.format("%.1f", av.x) + "," + String.format("%.1f", av.y) + "," +
                    String.format("%.1f", av.z) + ")" +
                    " dmg=" + String.format("%.1f", amount) +
                    " blockingBefore=" + wasBlocking +
                    " isBlocking=" + this.isBlocking +
                    " yaw=" + String.format("%.1f", this.yRot) +
                    " headRot=" + String.format("%.1f", this.getYHeadRot()) +
                    " arrowYaw=" + String.format("%.1f", arrowYawDeg) +
                    " facingErr=" + String.format("%.1f", facingErr) +
                    " offhand=" + MaidDebug.itemName(shieldSlot) +
                    " usingTicks=" + (if (this.isUsingItem) this.ticksUsingItem else -1)
            )
        }
        return result
    }

    private fun captureAttacker(source: DamageSource) {
        var attacker = source.entity
        if (attacker == null) {
            attacker = source.directEntity
        }
        this.lastAttacker = attacker
        this.lastAttackerTick = this.tickCount
    }

    // ---------------------------------------------------------------------
    // 背包 / 装备
    // ---------------------------------------------------------------------

    /** 把背包中的主手/副手/盔甲槽同步到实体的真实装备栏（服务端，保证穿戴/渲染/护甲生效） */
    fun syncInventoryArmor() {
        if (this.level().isClientSide) {
            return
        }
        // 主手：手持热键栏第 0 格（默认手持位置，仿玩家主手逻辑，任意物品可拿）
        syncSlot(EquipmentSlot.MAINHAND, 0, false)
        // 盔甲：只允许对应部位的装备
        syncSlot(EquipmentSlot.HEAD, 36, true)
        syncSlot(EquipmentSlot.CHEST, 37, true)
        syncSlot(EquipmentSlot.LEGS, 38, true)
        syncSlot(EquipmentSlot.FEET, 39, true)
        // 副手：像玩家一样可放任意物品
        syncSlot(EquipmentSlot.OFFHAND, 40, false)
    }

    private fun syncSlot(slot: EquipmentSlot, index: Int, requireEquippable: Boolean) {
        val stored = this.maidInventory.getItem(index)
        // 校验：盔甲槽只有能装备在该部位的物品才上装备栏，其余一律移除（防止"穿戴任何物品"）；
        // 主手/副手与玩家逻辑一致，任意物品均可持有，不做校验
        if (!stored.isEmpty && requireEquippable && !this.isEquippableInSlot(stored, slot)) {
            MaidDebug.log(
                "盔甲槽校验失败移除: slot=" + slot.getName() + " item=" + stored.item.getDescriptionId()
            )
            // 放错槽位时气泡提示主人
            this.showBubble(Component.literal("放错位置啦：这不是" + slot.getName() + "槽"), 60)
            this.setItemSlot(slot, ItemStack.EMPTY)
            return
        }
        val current = this.getItemBySlot(slot)
        if (!ItemStack.isSameItemSameComponents(current, stored)) {
            this.setItemSlot(slot, stored)
        }
    }

    /** 把物品放入背包区（0-35）第一个空槽，背包满则返回原物品。 */
    fun moveToBackpack(stack: ItemStack): ItemStack {
        for (i in 0 until 36) {
            if (this.maidInventory.getItem(i).isEmpty) {
                this.maidInventory.setItem(i, stack)
                return ItemStack.EMPTY
            }
        }
        return stack
    }

    // ---------------------------------------------------------------------
    // Goal
    // ---------------------------------------------------------------------

    protected override fun registerGoals() {
        this.goalSelector.addGoal(0, FloatGoal(this))
        // P5 战斗：战斗 Goal（优先级高于跟随，有目标时优先战斗）
        val combat = MaidCombatGoal(this)
        this.combatGoal = combat
        this.goalSelector.addGoal(1, combat)
        // L1 规则：跟随主人（距离/开关由女仆设置实时控制）
        this.goalSelector.addGoal(2, MaidFollowGoal(this, 1.0))
        // P4 空闲行为：随机散步 / 环视（AI 任务运行时散步让路）
        this.goalSelector.addGoal(3, AiAwareRandomStrollGoal())
        this.goalSelector.addGoal(4, RandomLookAroundGoal(this))

        // P5 目标选择：护主模式实时决定启用哪些目标来源
        //   主动 ACTIVE：主人被打 / 主人攻击的目标 / 附近敌对生物，都会索敌
        //   被动 PASSIVE：只在主人被打时反击
        //   关闭 OFF：不主动索敌
        val self = this
        this.targetSelector.addGoal(1, object : OwnerHurtByTargetGoal(self) {
            override fun canUse(): Boolean {
                return self.getSettings().protectMode != MaidSettings.ProtectMode.OFF && super.canUse()
            }
        })
        this.targetSelector.addGoal(2, object : OwnerHurtTargetGoal(self) {
            override fun canUse(): Boolean {
                return self.getSettings().protectMode == MaidSettings.ProtectMode.ACTIVE && super.canUse()
            }
        })
        this.targetSelector.addGoal(
            3,
            object : NearestAttackableTargetGoal<Monster>(
                self, Monster::class.java, false,
                TargetingConditions.Selector { target, _ ->
                    MaidTargetFilter.isHostile(self, target)
                }
            ) {
                override fun canUse(): Boolean {
                    return self.getSettings().protectMode == MaidSettings.ProtectMode.ACTIVE && super.canUse()
                }
            }
        )
    }

    protected override fun createNavigation(level: Level): PathNavigation {
        // 原版地面导航 + 自定义节点评估器（允许跳跃跨越障碍/沟的寻路）
        return MaidGroundPathNavigation(this, level)
    }

    // ---------------------------------------------------------------------
    // tick
    // ---------------------------------------------------------------------

    override fun aiStep() {
        super.aiStep()
        if (this.level().isClientSide) {
            // 客户端：推进女仆动作动画 tick（/maidanim 播放的 Emotecraft 动作）
            com.oyxdsg.smartmaid.client.animation.MaidAnimManager.tick(this)
            // 挥动动画推进：怎么做是版本敏感的
            //（26.2 要手动补推进；26.3 原版已统一处理）→ 走版本差异隔离层
            com.oyxdsg.smartmaid.compat.MaidCompat.tickSwingAnim(this)
        } else {
            // L0 本地安全层：每 tick 硬编码规则评估，优先级最高
            this.applySafetyRules()
            // 饱食度：仿玩家回血/饥饿（内部状态）
            val serverLevel = this.level() as? ServerLevel
            if (serverLevel != null) {
                this.maidFood.tick(serverLevel, this)
            }
            // 调试：饱食度/回血状态（verbose 门控，避免长期刷屏）
            if (MaidDebug.verbose() && this.tickCount % 40 == 0) {
                MaidDebug.log(
                    "Maid food level=" + this.maidFood.getFoodLevel() +
                        " sat=" + MaidDebug.fmt1(this.maidFood.getSaturationLevel().toDouble()) +
                        " hp=" + MaidDebug.fmt1(this.health.toDouble()) + "/" +
                        MaidDebug.fmt1(this.maxHealth.toDouble())
                )
            }
            // L1 移动兜底：距离主人过远时直接传送回主人身边
            this.applyTeleportFallback()
            // 聊天气泡倒计时：到期恢复默认名字
            if (this.bubbleTicksLeft > 0) {
                this.bubbleTicksLeft--
                if (this.bubbleTicksLeft == 0) {
                    this.entityData.set(DATA_BUBBLE, "")
                }
            }
            // 坐下 = 待命：锁死移动（不跟随 / 不散步 / 不跳跃 / 不被击退推走）+ 清空攻击目标
            this.applySitLock()
            // 坐下状态低频观测（verbose 门控）：确认坐下期间坐标不变（排查"坐着走"）
            if (MaidDebug.verbose() && this.isOrderedToSit && this.tickCount % 40 == 0) {
                MaidDebug.log(
                    "Maid sit 保持 pos=" + MaidDebug.fmt1(this.x) + "," +
                        MaidDebug.fmt1(this.y) + "," + MaidDebug.fmt1(this.z) +
                        " onGround=" + this.onGround()
                )
            }
            // 背包盔甲/副手槽 → 真实装备栏同步（穿戴生效）
            if (this.tickCount % 10 == 0) {
                this.syncInventoryArmor()
            }
            // 调试：服务端装备状态（每 40 tick，高噪日志由 verbose() 单独开启，避免长期累积）
            if (MaidDebug.verbose() && this.tickCount % 40 == 0) {
                MaidDebug.log(
                    "服务端装备: mainhand=" + MaidDebug.itemName(this.getItemBySlot(EquipmentSlot.MAINHAND)) +
                        " offhand=" + MaidDebug.itemName(this.getItemBySlot(EquipmentSlot.OFFHAND)) +
                        " head=" + MaidDebug.itemName(this.getItemBySlot(EquipmentSlot.HEAD)) +
                        " chest=" + MaidDebug.itemName(this.getItemBySlot(EquipmentSlot.CHEST)) +
                        " inv0=" + MaidDebug.itemName(this.maidInventory.getItem(0)) +
                        " inv40=" + MaidDebug.itemName(this.maidInventory.getItem(40))
                )
            }
            // 动作执行器：跨沟/跳越精确执行（坐下时不执行跳跃）
            if (!this.isOrderedToSit) {
                this.actionExecutor.tick()
            }
            // AI 任务调度器：驱动当前 AI 任务（攻击/挖矿/耕作/建造/制作）
            try {
                this.maidTaskManager.tick()
            } catch (cause: Throwable) {
                if (cause is VirtualMachineError || cause is ThreadDeath) throw cause
                SmartMaid.LOGGER.error("女仆任务管理器 tick 异常", cause)
                this.maidTaskManager.cancel()
            }
            // 感知模块：采样"玩家视角"快照 + 事件队列
            this.perceptionModule.tick()
            // 遥测下行（M5-a）：把女仆事件按窗口写入 deskpet/maid/，供桌面宠物读取
            MaidTelemetryWriter.onMaidTick(this)
            // 桌宠联动（M5-b）：注册 WebSocket 通道并订阅感知事件
            MaidWsClient.onMaidTick(this)
            // 移动调试监测：玩家/女仆状态 + 周围地形（开发用）
            MaidMonitor.tick(this)
            // 定期把装备/物品栏数据保存到本地文件（退出游戏后重新召唤可恢复）
            if (this.tickCount % 100 == 0) {
                MaidDataManager.save(this)
            }
        }
    }

    /**
     * L0 — 本地安全规则（物理级硬编码，断网/无 AI 也生效）。
     */
    private fun applySafetyRules() {
        val underPos = this.blockPosition().below()
        val below: BlockState = this.level().getBlockState(underPos)
        val inLavaLike = below.block == Blocks.LAVA ||
            below.block == Blocks.FIRE ||
            below.block == Blocks.MAGMA_BLOCK
        if (inLavaLike && !this.isInWater && !this.isInLava) {
            this.jumpFromGround()
            this.deltaMovement = this.deltaMovement.add(
                this.random.nextGaussian() * 0.3, 0.3, this.random.nextGaussian() * 0.3
            )
        }
        // 生命值过低且处于危险液体中：强制脱离
        if (this.health <= 3.0F && (this.isInWater || inLavaLike)) {
            this.jumpFromGround()
        }
    }

    /**
     * L1 — 移动兜底规则：距离主人过远时直接传送回主人身边。
     *
     * 触发条件：主人有效、非坐下/乘骑/被拴绳（[unableToMoveToOwner]），且与主人距离
     * 超过 [TELEPORT_FALLBACK_DISTANCE_SQR] 的平方根。先尝试原版 [tryToTeleportToOwner]
     * （在主人周围随机找安全落点，最多 10 次），失败则硬传送到主人脚下，
     * 避免女仆寻路失败/卡死后永久丢失在远处。
     */
    private fun applyTeleportFallback() {
        // 坐下 = 原地待命：不追主人、也不传送（主子走远了就留在原地等她回来）
        if (this.isOrderedToSit) {
            return
        }
        if (this.teleportFallbackCooldown > 0) {
            this.teleportFallbackCooldown--
            return
        }
        val owner = this.owner
        if (owner == null || this.unableToMoveToOwner()) {
            return
        }
        if (this.distanceToSqr(owner) < TELEPORT_FALLBACK_DISTANCE_SQR) {
            return
        }
        this.tryToTeleportToOwner()
        // 原版找点可能失败（如主人周围全是悬崖/水）：硬传送到主人脚下兜底
        if (this.distanceToSqr(owner) >= TELEPORT_FALLBACK_DISTANCE_SQR) {
            this.snapTo(owner.x, owner.y + 1.0, owner.z, owner.yRot, owner.xRot)
        }
        this.teleportFallbackCooldown = 100
    }

    /**
     * 显示聊天气泡：头顶文字持续 ticks tick 后消失，并恢复默认名字。
     *
     * 当前用名字标签（customName）实现，跟随原版名字渲染，零自定义渲染代码；
     * 后续可升级为带面板的气泡渲染。
     */
    fun showBubble(text: Component, ticks: Int) {
        if (this.level().isClientSide) {
            return
        }
        if (this.bubbleTicksLeft <= 0) {
            // 记下气泡期间的名字，供感知/遥测上报使用（避免上报气泡文案）
            this.nameBeforeBubble = this.getName().string
        }
        // 气泡走独立同步字段（客户端渲染器拆成多行），不再顶替 customName
        this.entityData.set(DATA_BUBBLE, text.string)
        this.bubbleTicksLeft = max(ticks, 1)
    }

    /**
     * 稳定显示名：气泡占用 customName 期间返回气泡前的名字。
     *
     * 感知/遥测上报一律用这个名字——否则每弹一次气泡，上报的 name
     * 就会在「女仆 ↔ 气泡文案」之间跳变，既误导 AI 又制造无谓的增量 diff。
     */
    fun baseName(): String {
        return if (this.bubbleTicksLeft > 0 && this.nameBeforeBubble != null) {
            this.nameBeforeBubble!!
        } else {
            this.getName().string
        }
    }

    /**
     * 强制传送回主人身边（GUI「召回」按钮）。
     * 先尝试原版安全找点，未贴近则硬传送到主人脚下。
     */
    fun teleportToOwner() {
        val owner = this.owner
        if (owner == null || this.level().isClientSide) {
            return
        }
        this.tryToTeleportToOwner()
        if (this.distanceToSqr(owner) > 9.0) {
            this.snapTo(owner.x, owner.y + 1.0, owner.z, owner.yRot, owner.xRot)
        }
        this.teleportFallbackCooldown = 100
    }

    // ---------------------------------------------------------------------
    // 拾取 / 物品存储
    // ---------------------------------------------------------------------

    override fun wantsToPickUp(level: ServerLevel, stack: ItemStack): Boolean {
        // 刚把东西丢给主人时短暂不捡，避免「丢出去又立刻被捡回」。
        if (this.tickCount < this.pickupSuppressUntil) {
            return false
        }
        // 其余情况女仆拾取所有掉落物（放入女仆背包）
        return true
    }

    /** 让女仆在接下来 ticks tick 内不拾取任何掉落物（丢给主人后调用，防捡回）。 */
    fun suppressPickup(ticks: Int) {
        this.pickupSuppressUntil = max(this.pickupSuppressUntil, this.tickCount + max(0, ticks))
    }

    override fun equipItemIfPossible(level: ServerLevel, stack: ItemStack): ItemStack {
        // 照抄原版语义：返回「成功取走的量」（非空 = 取走这么多），剩余留在掉落物里。
        // 只放入背包/热键区（0-35），绝不进入盔甲/副手槽（避免"穿上任何物品"）。
        var remaining = this.addToMaidStorage(stack)
        if (remaining.count == stack.count && MaidInventoryTidy.tidyIfStuck(this)) {
            // 一件都没放进（背包满/碎片化）→ 整理后重试一次，避免掉落物一直捡不起来
            remaining = this.addToMaidStorage(stack)
        }
        val taken = stack.count - remaining.count
        if (taken <= 0) {
            return ItemStack.EMPTY
        }
        val result = stack.copy()
        result.count = taken
        return result
    }

    /** 把物品放入女仆背包区（0-35，热键+背包），返回未放入的剩余。照抄原版 SimpleContainer.addItem，但限定槽位范围。 */
    private fun addToMaidStorage(stack: ItemStack): ItemStack {
        var remaining = stack.copy()
        // 先与同类物品堆叠
        var i = 0
        while (i < 36 && !remaining.isEmpty) {
            val existing = this.maidInventory.getItem(i)
            if (!existing.isEmpty && ItemStack.isSameItemSameComponents(existing, remaining) &&
                existing.count < existing.maxStackSize
            ) {
                val room = min(remaining.count, existing.maxStackSize - existing.count)
                existing.grow(room)
                remaining.shrink(room)
            }
            i++
        }
        // 再放入空槽
        if (!remaining.isEmpty) {
            var j = 0
            while (j < 36 && !remaining.isEmpty) {
                if (this.maidInventory.getItem(j).isEmpty) {
                    this.maidInventory.setItem(j, remaining)
                    remaining = ItemStack.EMPTY
                }
                j++
            }
        }
        this.maidInventory.setChanged()
        return remaining
    }

    // ---------------------------------------------------------------------
    // 死亡 / 交互 / 生命周期
    // ---------------------------------------------------------------------

    protected override fun dropAllDeathLoot(level: ServerLevel, source: DamageSource) {
        // 遥测：死亡即释放窗口缓冲与事件回调
        MaidTelemetryWriter.forget(this)
        // 告知桌宠女仆已离线（桌宠可据此恢复桌面窗口）
        MaidWsClient.notifyPresence(this, false)
        // 装备槽统一由下方背包全量掉落处理：先清零装备掉落率，避免主手/副手物品（同时存在于背包槽与装备槽）重复掉落
        for (slot in EquipmentSlot.VALUES) {
            this.setDropChance(slot, 0.0F)
        }
        super.dropAllDeathLoot(level, source)
        // 按设置决定掉落范围：全部 41 格（热键0-8/背包9-35/盔甲36-39/副手40）/ 只掉背包0-35 / 全保留
        val mode = this.getSettings().deathDropMode
        val dropUpTo = when (mode) {
            MaidSettings.DeathDropMode.ALL -> this.maidInventory.containerSize
            MaidSettings.DeathDropMode.KEEP_EQUIPMENT -> 36
            MaidSettings.DeathDropMode.NONE -> 0
        }
        for (i in 0 until dropUpTo) {
            val stack = this.maidInventory.getItem(i)
            if (!stack.isEmpty) {
                this.spawnAtLocation(level, stack)
                this.maidInventory.setItem(i, ItemStack.EMPTY)
            }
        }
        // 保留未掉落的物品到本地存档，重新召唤时恢复（全部掉落时等价于空存档）
        MaidDataManager.save(this)
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        // 唯一女仆：召唤即归属玩家。
        // 主人空手右键 → 坐下/站起；Shift+右键 → 女仆管理菜单（Shift+E 打开女仆背包，两者并存）
        if (this.isOwnedBy(player) && player.getItemInHand(hand).isEmpty) {
            if (player.isShiftKeyDown) {
                if (!player.level().isClientSide && player is ServerPlayer) {
                    // N1：Shift+右键 改开客户端纯 Screen 主菜单（不挂容器）
                    ServerPlayNetworking.send(
                        player,
                        MaidOpenMenuPayload(this.id, this.getMaidFood().getFoodLevel())
                    )
                    ModNetworking.sendMenuState(player, this)
                }
                return InteractionResult.SUCCESS
            }
            if (!player.level().isClientSide) {
                // 坐/站统一切换：setOrderedToSit 内部处理姿摆、落地吸附、移动锁定
                this.setOrderedToSit(!this.isOrderedToSit)
            }
            return InteractionResult.SUCCESS
        }
        return super.mobInteract(player, hand)
    }

    override fun onRemoval(reason: RemovalReason) {
        // 区块卸载直接调用 setRemoved，必须在 onRemoval 保存；死亡已在掉落后保存。
        if (!this.level().isClientSide && reason != RemovalReason.KILLED) {
            MaidDataManager.save(this)
        }
        super.onRemoval(reason)
    }

    override fun getBreedOffspring(level: ServerLevel, parent: AgeableMob): AgeableMob? {
        // MVP 不支持繁殖
        return null
    }

    override fun isFood(stack: ItemStack): Boolean {
        // 关闭原版繁殖流程，驯服逻辑在 mobInteract 中处理
        return false
    }

    /** 随机散步 Goal：AI 任务运行时让路，避免抢占移动控制；坐下（待命）时不许散步 */
    private inner class AiAwareRandomStrollGoal : RandomStrollGoal(this@SmartMaidEntity, 0.6) {
        override fun canUse(): Boolean {
            return !this@SmartMaidEntity.isAiBusy &&
                !this@SmartMaidEntity.isOrderedToSit &&
                super.canUse()
        }

        override fun canContinueToUse(): Boolean {
            // 坐下瞬间若正在散步：立即停（否则会"坐着走"）
            return !this@SmartMaidEntity.isOrderedToSit && super.canContinueToUse()
        }
    }

    companion object {
        /** 开发调试：强制播放的动画 id（0=无，由 /maidanim 指令设置，同步到客户端渲染） */
        private val DATA_DEBUG_ANIM: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(SmartMaidEntity::class.java, EntityDataSerializers.INT)

        /** 头顶气泡文本（同步到客户端；渲染器按宽度拆成多行显示；空串=无气泡） */
        private val DATA_BUBBLE: EntityDataAccessor<String> =
            SynchedEntityData.defineId(SmartMaidEntity::class.java, EntityDataSerializers.STRING)

        /** 默认显示名（气泡结束后恢复） */
        private val DISPLAY_NAME: Component = Component.literal("女仆")

        /** 移动兜底距离阈值：与主人距离超过该值（平方）直接传送回主人身边 */
        private const val TELEPORT_FALLBACK_DISTANCE_SQR: Double = 32.0 * 32.0

        /** 女仆近战实际可攻击距离（格）：判定边界为 3.0，留 0.2 缓冲避免"判定到了够不着" */
        const val MAID_ATTACK_REACH: Double = 3.2

        /** 实体属性注册（对应 Java `public static AttributeSupplier.Builder createAttributes()`）。 */
        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder {
            return createMobAttributes()
                .add(Attributes.MAX_HEALTH, 24.0)
                // 速度：0.16 是能覆盖全部跳跃场景（跨3格沟+上1格）的最小值（离线解表标定）
                .add(Attributes.MOVEMENT_SPEED, 0.16)
                .add(Attributes.ATTACK_DAMAGE, 4.0)
                // 攻击速度基线（玩家同为 4.0），供武器攻速解析（近战 DPS 评分）使用
                .add(Attributes.ATTACK_SPEED, 4.0)
                // 攻击/感知范围：决定索敌与追击距离
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.ATTACK_KNOCKBACK, 0.4)
        }
    }
}
