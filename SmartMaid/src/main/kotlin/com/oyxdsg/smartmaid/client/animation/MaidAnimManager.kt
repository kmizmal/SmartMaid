package com.oyxdsg.smartmaid.client.animation

import com.oyxdsg.smartmaid.SmartMaid
import com.oyxdsg.smartmaid.compat.MaidAnimCompat
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import com.zigythebird.playeranim.util.RenderUtil
import com.zigythebird.playeranimcore.animation.Animation
import com.zigythebird.playeranimcore.animation.AnimationData
import com.zigythebird.playeranimcore.animation.HumanoidAnimationController
import com.zigythebird.playeranimcore.bones.PlayerAnimBone
import com.zigythebird.playeranimcore.enums.PlayState
import com.zigythebird.playeranimcore.loading.UniversalAnimLoader
import net.minecraft.client.model.geom.ModelPart

/**
 * 女仆动作管理：加载 Emotecraft 动作 JSON → PAL Animation，用 [HumanoidAnimationController] 播放。
 *
 * PAL 的 [HumanoidAnimationController] 不依赖 Avatar，女仆可直接使用：
 * 1. 每游戏 tick：[tick] 推进控制器（动画时间 +1）。
 * 2. 渲染 setupAnim：[apply] 读取骨骼值写入模型。
 *
 * 动作 JSON（Emotecraft 格式）由 [UniversalAnimLoader] 解析，骨骼名自动归一化
 * （rightArm → right_arm），与 [HumanoidAnimationController] 注册的骨骼一致。
 */
class MaidAnimManager private constructor() {
    companion object {
        /** 动作 id → 动作名（与 /maidanim 指令对应） */
        private val ANIM_NAMES: Array<String> = arrayOf(
            "",                     // 0 = 无
            "waving",               // 1 挥手
            "clap",                 // 2 鼓掌
            "point",                // 3 指
            "palm",                 // 4 展示手掌
            "here",                 // 5 这里
            "crying",               // 6 哭
            "backflip",             // 7 后空翻
            "twerk",                // 8 电臀舞
            "kazotsky_kick",         // 9 哥萨克踢腿舞
            "roblox_potion_dance",   // 10 Roblox 药水舞
            "club_penguin_dance",    // 11 企鹅俱乐部舞
        )

        /** 已加载动作：动作名 → Animation（纯数据，服务端/客户端共用） */
        private val ANIMATIONS: MutableMap<String, Animation> = HashMap()

        /** 女仆实体 id → 动画控制器（客户端渲染用） */
        private val CONTROLLERS: MutableMap<Int, HumanoidAnimationController> = HashMap()

        /** 女仆实体 id → 当前播放动作名 */
        private val CURRENT: MutableMap<Int, String> = HashMap()

        private var loaded: Boolean = false

        private fun ensureLoaded() {
            if (loaded) {
                return
            }
            for (name in ANIM_NAMES) {
                if (name.isEmpty()) {
                    continue
                }
                try {
                    MaidAnimManager::class.java.getResourceAsStream("/assets/smartmaid/emotes/$name.json").use { input ->
                        if (input == null) {
                            SmartMaid.LOGGER.warn("动作文件缺失: {}", name)
                        } else {
                            val map = UniversalAnimLoader.loadAnimations(input)
                            // map 的 key 是动作内部名称（Component 翻译键），用文件名作为 key 存储，便于按名字播放
                            val anim = map.values.stream().findFirst().orElse(null)
                            if (anim != null) {
                                ANIMATIONS[name] = anim
                            } else {
                                SmartMaid.LOGGER.warn("动作 {} 解析为空", name)
                            }
                        }
                    }
                } catch (e: Exception) {
                    SmartMaid.LOGGER.error("加载动作 {} 失败", name, e)
                }
            }
            loaded = true
            SmartMaid.LOGGER.info("已加载 {} 个动作: {}", ANIMATIONS.size, ANIMATIONS.keys)
        }

        @JvmStatic
        fun getAnimNames(): Array<String> = ANIM_NAMES

        private fun getController(maid: SmartMaidEntity): HumanoidAnimationController {
            return CONTROLLERS.computeIfAbsent(maid.getId()) {
                HumanoidAnimationController(
                    { _, _, _ -> PlayState.CONTINUE },
                    // 表达式引擎由版本隔离层提供；由 createEngine 的返回值匹配当前 PAL 构造器。
                    { controller -> MaidAnimCompat.createEngine(controller) }
                )
            }
        }

        /** 播放指定动作（0=停止），服务端指令调用后同步到客户端通过实体数据 */
        @JvmStatic
        fun play(maid: SmartMaidEntity, animId: Int) {
            ensureLoaded()
            val name = if (animId >= 0 && animId < ANIM_NAMES.size) ANIM_NAMES[animId] else ""
            val eid = maid.getId()
            val ctrl = getController(maid)

            val cur = CURRENT[eid]
            if (name == cur) {
                return
            }
            if (cur != null) {
                ctrl.stopTriggeredAnimation()
                CURRENT.remove(eid)
            }
            if (name.isEmpty()) {
                return
            }
            val anim = ANIMATIONS[name]
            if (anim == null) {
                SmartMaid.LOGGER.warn("动作未找到: {}", name)
                return
            }
            ctrl.triggerAnimation(anim)
            CURRENT[eid] = name
            SmartMaid.LOGGER.debug("女仆 {} 播放动作 {}", eid, name)
        }

        /** 每游戏 tick 推进动画时间（实体 aiStep 客户端调用，PAL 需要 tick 字段递增） */
        @JvmStatic
        fun tick(maid: SmartMaidEntity) {
            val ctrl = CONTROLLERS[maid.getId()]
            if (ctrl == null || !ctrl.isActive()) {
                return
            }
            val h = Math.abs(maid.getDeltaMovement().x) + Math.abs(maid.getDeltaMovement().z)
            ctrl.tick(AnimationData((h / 2.0).toFloat(), 0.0f, false))
        }

        /**
         * 每渲染帧计算动画骨骼（setupAnim 中调用）。
         * process 用 tick + partialTick 计算当前关键帧值写入 activeBones，之后 get3DTransform 读取。
         */
        @JvmStatic
        fun processOnce(maid: SmartMaidEntity, tickDelta: Float) {
            val ctrl = CONTROLLERS[maid.getId()]
            if (ctrl == null || !ctrl.isActive()) {
                return
            }
            val h = Math.abs(maid.getDeltaMovement().x) + Math.abs(maid.getDeltaMovement().z)
            ctrl.setupAnim(AnimationData((h / 2.0).toFloat(), tickDelta, false))
        }

        /** 是否正在播放动作 */
        @JvmStatic
        fun isPlaying(maid: SmartMaidEntity): Boolean = CURRENT.containsKey(maid.getId())

        /**
         * 把骨骼变换应用到模型部件（setupAnim 中调用，需先 [processOnce]）。
         * 骨骼名用标准下划线格式（right_arm/left_arm/right_leg/left_leg/head/torso/body）。
         */
        @JvmStatic
        fun apply(maid: SmartMaidEntity, boneName: String, part: ModelPart) {
            val ctrl = CONTROLLERS[maid.getId()]
            if (ctrl == null || !ctrl.isActive()) {
                return
            }
            val bone = PlayerAnimBone(boneName)
            ctrl.get3DTransform(bone)
            RenderUtil.translatePartToBone(part, bone, part.getInitialPose())
        }

        /** 实体移除时清理控制器 */
        @JvmStatic
        fun cleanup(entityId: Int) {
            CONTROLLERS.remove(entityId)
            CURRENT.remove(entityId)
        }
    }
}
