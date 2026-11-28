package com.oyxdsg.smartmaid.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.client.animation.MaidAnimManager
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.oyxdsg.smartmaid.entity.ai.MaidDebug
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.ArmorModelSet
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import kotlin.math.min

/**
 * 女仆渲染器（Java 版玩家模型 + 女仆皮肤）。
 * 坐姿：坐下时标记 isPassenger，走原版骑乘坐姿（僵尸坐船同款），并整体下沉
 * [SIT_SINK] 格让屁股落到脚下支撑面（地面 / 半砖 / 台阶顶），不再浮空。
 * 装备：加原版盔甲渲染层，穿戴外观正常显示。
 */
open class SmartMaidRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<SmartMaidEntity, SmartMaidRenderState, SmartMaidModel>(
        context,
        SmartMaidModel(context.bakeLayer(ModelLayers.PLAYER)),
        0.5f
    ) {

    companion object {
        /** 气泡每行字符数（中文按字宽）。 */
        private const val PER_LINE = 14

        /**
         * 坐姿下沉量（格）：玩家模型髋部（body/腿的 pivot）在 12px = 0.75 格处。
         * 坐下时把整个模型下沉该距离，让屁股/大腿正好落到脚下支撑面
         * （地面 / 半砖顶 0.5 / 台阶顶 1.0）上，而不是浮在空中。
         */
        private const val SIT_SINK = 0.75f
    }

    /** 上一次打印"客户端主手"的 tick（渲染帧调用远多于 tick，用它去重，避免每帧刷屏） */
    private var lastHandLogTick = -1

    init {
        // 盔甲渲染层（照抄原版）：先把 PLAYER_ARMOR（layer location 集合）bake 成 HumanoidModel，再挂到渲染层
        val armorSet: ArmorModelSet<HumanoidModel<SmartMaidRenderState>> =
            ArmorModelSet.bake(
                ModelLayers.PLAYER_ARMOR,
                context.modelSet
            ) { root -> HumanoidModel(root) }
        addLayer(
            HumanoidArmorLayer(
                this, armorSet, context.equipmentRenderer
            )
        )
    }

    override fun createRenderState(): SmartMaidRenderState = SmartMaidRenderState()

    override fun getArmPose(entity: SmartMaidEntity, arm: HumanoidArm): HumanoidModel.ArmPose {
        // 仿玩家：空手自然下垂；手持物品抬起手臂；使用中按物品动作切换姿势
        // （拉弓 BOW_AND_ARROW / 举盾 BLOCK / 望远镜等——否则会被一律 ITEM 顶掉）
        val held: ItemStack = entity.getItemHeldByArm(arm)
        if (held.isEmpty) {
            return HumanoidModel.ArmPose.EMPTY
        }
        val hand: InteractionHand =
            if (arm == entity.mainArm) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
        if (entity.isUsingItem && entity.usedItemHand == hand) {
            return when (held.useAnimation) {
                net.minecraft.world.item.ItemUseAnimation.BLOCK -> HumanoidModel.ArmPose.BLOCK
                net.minecraft.world.item.ItemUseAnimation.BOW -> HumanoidModel.ArmPose.BOW_AND_ARROW
                net.minecraft.world.item.ItemUseAnimation.TRIDENT -> HumanoidModel.ArmPose.THROW_TRIDENT
                net.minecraft.world.item.ItemUseAnimation.CROSSBOW -> HumanoidModel.ArmPose.CROSSBOW_CHARGE
                net.minecraft.world.item.ItemUseAnimation.SPYGLASS -> HumanoidModel.ArmPose.SPYGLASS
                net.minecraft.world.item.ItemUseAnimation.TOOT_HORN -> HumanoidModel.ArmPose.TOOT_HORN
                net.minecraft.world.item.ItemUseAnimation.BRUSH -> HumanoidModel.ArmPose.BRUSH
                net.minecraft.world.item.ItemUseAnimation.SPEAR -> HumanoidModel.ArmPose.SPEAR
                else -> HumanoidModel.ArmPose.ITEM
            }
        }
        return HumanoidModel.ArmPose.ITEM
    }

    /**
     * 坐姿整体下沉（世界坐标，Y 向上）。
     *
     * 调用时机：原版 `LivingEntityRenderer.submit` 在 `scale(state.scale)` 之后、
     * 模型的 `scale(-1,-1,1)` 翻转**之前**调用本方法，所以这里的 Y 就是世界的"上"，
     * 直接向下平移即可（不会撞模型坐标系 y 向下的约定）。
     *
     * 只对"坐下"生效：真正的骑乘（船/矿车）仍由原版处理，不下沉。
     */
    override fun setupRotations(
        state: SmartMaidRenderState,
        poseStack: PoseStack,
        bodyRot: Float,
        scale: Float
    ) {
        super.setupRotations(state, poseStack, bodyRot, scale)
        if (state.sitting) {
            poseStack.translate(0.0f, -SIT_SINK, 0.0f)
        }
    }

    override fun extractRenderState(entity: SmartMaidEntity, state: SmartMaidRenderState, tickDelta: Float) {
        // 女仆 = 玩家：super 已提取玩家模型需要的全部状态（mainHandItem/offHandItem/swimAmount/isFallFlying/攻击摆动等），
        // 我们不覆盖任何动作状态，保证行走/游泳/攀爬/攻击等玩家原生动作全部生效。
        super.extractRenderState(entity, state, tickDelta)
        // 坐姿映射：女仆坐下 → 标记 isPassenger，走原版玩家骑乘坐姿（玩家坐船同款）。
        // 注意：只在坐下时置 true，不覆盖真正的骑乘状态（女仆坐船时应保持原版骑乘）
        state.sitting = entity.isOrderedToSit || entity.isInSittingPose
        if (state.sitting) {
            state.isPassenger = true
            // 模型整体下沉后，名字标签同步下沉，避免头顶与名字之间空出一大截
            val attachment = state.nameTagAttachment
            if (attachment != null) {
                state.nameTagAttachment = attachment.add(0.0, -SIT_SINK.toDouble(), 0.0)
            }
        }
        // 开发调试动画 id（/maidanim 设置，同步到客户端）
        state.debugAnim = entity.getDebugAnim()
        state.tickDelta = tickDelta
        state.maid = entity
        // 头顶气泡：按宽度拆成多行，气泡期间隐藏原版单行名字标签
        state.bubbleLines = splitBubble(entity.getBubbleText())
        if (state.bubbleLines.isNotEmpty()) {
            state.nameTag = null
        }
        // 触发动作播放（客户端，幂等：同一 id 不重复播放）
        MaidAnimManager.play(entity, state.debugAnim)
        // 诊断：客户端实体主手物品（每 5s 一条；extractRenderState 每帧都会被调用，必须按 tick 去重）
        if (entity.tickCount % 100 == 0 && entity.tickCount != lastHandLogTick) {
            lastHandLogTick = entity.tickCount
            MaidDebug.log("Render 客户端主手: " + MaidDebug.itemName(entity.getItemHeldByArm(entity.mainArm)))
        }
        // 调试：确认 extractRenderState 被调用（每 40 tick，高噪由 verbose() 单独开启）
        if (MaidDebug.verbose() && entity.tickCount % 40 == 0) {
            MaidDebug.log(
                "extractRenderState called, sitting=" + state.sitting + " passenger=" + state.isPassenger
                    + " anim=" + state.debugAnim
                    + " head=" + MaidDebug.itemName(entity.getItemBySlot(EquipmentSlot.HEAD))
                    + " chest=" + MaidDebug.itemName(entity.getItemBySlot(EquipmentSlot.CHEST))
            )
        }
    }

    /**
     * 多行气泡渲染：气泡激活时逐行提交名字标签（每行独立背景），
     * 否则走原版单行名字标签。
     */
    override fun submitNameDisplay(
        state: SmartMaidRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState
    ) {
        val lines: List<Component> = state.bubbleLines
        if (lines.isEmpty()) {
            super.submitNameDisplay(state, poseStack, collector, camera)
            return
        }
        val base: Vec3? = state.nameTagAttachment
        val n = lines.size
        for (i in 0 until n) {
            // 第一行显示在最上：越靠前的行 Y 偏移越大
            val attach = base?.add(0.0, (n - 1 - i) * 0.30, 0.0)
            collector.submitNameTag(poseStack, attach, 0, lines[i], true, state.lightCoords, camera)
        }
    }

    /** 把长气泡文本按字符数拆成多行（中文按字宽），避免一整行横在头顶。 */
    private fun splitBubble(text: String?): List<Component> {
        if (text.isNullOrEmpty()) {
            return emptyList()
        }
        val lines = ArrayList<Component>()
        val len = text.length
        var i = 0
        while (i < len) {
            lines.add(Component.literal(text.substring(i, min(len, i + PER_LINE))))
            i += PER_LINE
        }
        return lines
    }

    override fun getTextureLocation(state: SmartMaidRenderState): Identifier =
        Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "textures/entity/smart_maid.png")
}
