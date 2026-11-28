package com.oyxdsg.smartmaid.compat

import com.mojang.blaze3d.platform.InputConstants
import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.client.Minecraft
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * 版本差异隔离层 —— **MC 26.2 实现**。
 *
 * 这个包（`src/mc26.2/kotlin`）里放的全是「只在某个 MC 版本上编译得过」的代码。
 * 共享代码一律只调本对象的静态方法，不直接碰版本敏感 API。
 *
 * `src/mc26.3/kotlin` 下有一份**同名对象**，签名完全一致、实现不同。构建时按
 * `mc_series` 只把其中一份加进 srcDirs。
 */
object MaidCompat {

    /** 挥动手臂。26.2：`swing(hand)`。 */
    @JvmStatic
    fun swing(entity: LivingEntity, hand: InteractionHand) {
        entity.swing(hand)
    }

    /**
     * 客户端推进挥动动画。
     *
     * 26.2 的 `LivingEntity.updateSwingTime()` 只在 `Monster`/`Player` 子类的 `aiStep`
     * 里被调用，普通 Mob（含女仆）收到挥动动画包后 `attackAnim` 永远不会推进 →
     * 挖掘/攻击挥动看不见。这里手动复制原版推进逻辑。挥动时长
     * `getCurrentSwingDuration()` 是 private，用反射取（含物品时长/挖掘加速）。
     */
    @JvmStatic
    fun tickSwingAnim(maid: SmartMaidEntity) {
        var duration = 6
        val m = SWING_DURATION_METHOD
        if (m != null) {
            try {
                duration = m.invoke(maid) as Int
            } catch (ignored: Exception) {
            }
        }
        if (duration <= 0) {
            duration = 6
        }
        if (maid.swinging) {
            maid.swingTime++
            if (maid.swingTime >= duration) {
                maid.swingTime = 0
                maid.swinging = false
            }
        } else {
            maid.swingTime = 0
        }
        maid.attackAnim = maid.swingTime.toFloat() / duration.toFloat()
    }

    private val SWING_DURATION_METHOD: java.lang.reflect.Method? = try {
        val m = LivingEntity::class.java.getDeclaredMethod("getCurrentSwingDuration")
        m.isAccessible = true
        m
    } catch (ignored: NoSuchMethodException) {
        null
    }

    /** 该物品能否当熔炉燃料。26.2：`Level#fuelValues().isFuel(stack)`。 */
    @JvmStatic
    fun isFuel(level: Level, stack: ItemStack): Boolean {
        if (stack.isEmpty || level !is ServerLevel) {
            return false
        }
        return level.fuelValues().isFuel(stack)
    }

    /** `KeyMapping` 用的输入类型。26.2：`KEYSYM`。 */
    @JvmStatic
    fun keyType(): InputConstants.Type = InputConstants.Type.KEYSYM

    /** 按键是否按下。26.2 需要显式传当前窗口。 */
    @JvmStatic
    fun isKeyDown(keyCode: Int): Boolean =
        InputConstants.isKeyDown(Minecraft.getInstance().window, keyCode)

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
     * 「鼠标主键（左键）」在本版本里的编码值 —— 26.2 用 GLFW：**左键 = 0**。
     *
     * ⚠️ 别硬编码 0：26.3 换成 SDL 后左键变成 **1**（右 3 / 中 2），
     * 沿用 `event.button() == 0` 会让**所有自绘点击静默失效**。
     */
    @JvmStatic
    fun primaryMouseButton(): Int = org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT

    /** 该鼠标键号是不是主键（左键）。 */
    @JvmStatic
    fun isPrimaryMouseButton(button: Int): Boolean = button == primaryMouseButton()
}
