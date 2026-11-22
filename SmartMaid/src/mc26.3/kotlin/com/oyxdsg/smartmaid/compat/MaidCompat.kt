package com.oyxdsg.smartmaid.compat

import com.mojang.blaze3d.platform.InputConstants
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.Level

/**
 * 版本差异隔离层 —— **MC 26.3 实现**。
 *
 * 与 `src/mc26.2/kotlin` 下那份同名对象**签名完全一致**、实现不同。
 * 构建时按 `gradle.properties` 的 `mc_series` 只把其中一份加进 srcDirs。
 */
object MaidCompat {

    /**
     * 挥动手臂。
     *
     * 26.3 把 `swing(InteractionHand)` / `swing(InteractionHand, boolean)` 换成了
     * `swing(InteractionHand, SwingAnimation, boolean)`，挥动时长改由**物品组件**决定。
     */
    @JvmStatic
    fun swing(entity: LivingEntity, hand: InteractionHand) {
        val stack = entity.getItemInHand(hand)
        val animation = stack.get(DataComponents.ATTACK_ANIMATION)
        entity.swing(hand, animation ?: SwingAnimation.DEFAULT, true)
    }

    /**
     * 客户端推进挥动动画。
     *
     * **26.3 起为 no-op。** 26.3 把挥动状态重构成了 `LivingEntity.SwingState` +
     * `getSwingAnimation(float)` + `isSwinging()`，那套「按子类手工推进」的字段与
     * 私有方法全部删除 —— 预期原版已统一处理，本 hack 不再需要。
     *
     * ⚠️ **未验证项**：26.3 上「普通 Mob 的挥动是否真的可见」只能真机确认。
     */
    @JvmStatic
    fun tickSwingAnim(maid: SmartMaidEntity) {
        // 26.3：原版统一处理，无需补推进。
    }

    /**
     * 该物品能否当熔炉燃料。
     *
     * 26.3 删除了 `FuelValues` 与 `Level#fuelValues()`，燃料改由物品组件
     * `DataComponents.COOKING_FUEL` 声明。
     */
    @JvmStatic
    fun isFuel(level: Level, stack: ItemStack): Boolean =
        !stack.isEmpty && stack.has(DataComponents.COOKING_FUEL)

    /** `KeyMapping` 用的输入类型。26.3：`KEYSYM` 已改名 `KEYBOARD`。 */
    @JvmStatic
    fun keyType(): InputConstants.Type = InputConstants.Type.KEYBOARD

    /** 按键是否按下。26.3 去掉了 `Window` 参数。 */
    @JvmStatic
    fun isKeyDown(keyCode: Int): Boolean = InputConstants.isKeyDown(keyCode)

    /** 任一按键按下即为真（用于「左右 Shift 任意一个」这类判定）。 */
    @JvmStatic
    fun isAnyKeyDown(vararg keyCodes: Int): Boolean {
        for (code in keyCodes) {
            if (isKeyDown(code)) {
                return true
            }
        }
        return false
    }

    /**
     * 「鼠标主键（左键）」在本版本里的编码值 —— 26.3 用 SDL：**左键 = 1**。
     *
     * ⚠️ 26.2（GLFW）左键是 0；26.3（SDL）左键是 **1**。`MouseButtonEvent.button()`
     * 直接把 SDL 的键号透传出来。
     */
    @JvmStatic
    fun primaryMouseButton(): Int = org.lwjgl.sdl.SDLMouse.SDL_BUTTON_LEFT

    /** 该鼠标键号是不是主键（左键）。 */
    @JvmStatic
    fun isPrimaryMouseButton(button: Int): Boolean = button == primaryMouseButton()
}
