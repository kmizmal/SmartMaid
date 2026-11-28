package com.oyxdsg.smartmaid.entity

import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.food.FoodProperties
import net.minecraft.world.level.gamerules.GameRules

/** 女仆饱食度（仿玩家 FoodData，仅内部状态）。 */
class MaidFoodData {
    companion object {
        private const val MAX_FOOD = 20
        private const val EXHAUSTION_THRESHOLD = 4.0f
    }

    private var foodLevel = MAX_FOOD
    private var saturationLevel = 5.0f
    private var exhaustionLevel = 0.0f
    private var tickTimer = 0
    private var hungerRate = 1.0f
    private var regenRate = 1.0f

    /** 应用玩家设置里的饱食消耗 / 回血速度倍率。 */
    fun setRates(hungerRate: Float, regenRate: Float) {
        this.hungerRate = maxOf(0.0f, hungerRate)
        this.regenRate = maxOf(0.1f, regenRate)
    }

    fun getFoodLevel(): Int = foodLevel

    fun getSaturationLevel(): Float = saturationLevel

    /** 进食：按食物营养 + 饱和度补充。 */
    fun eat(food: FoodProperties) {
        foodLevel = Mth.clamp(food.nutrition() + foodLevel, 0, MAX_FOOD)
        saturationLevel = Mth.clamp(food.saturation() + saturationLevel, 0.0f, foodLevel.toFloat())
    }

    /** 增加疲劳，按设置的饱食消耗速度倍率缩放。 */
    fun addExhaustion(amount: Float) {
        exhaustionLevel = minOf(exhaustionLevel + amount * hungerRate, 40.0f)
    }

    /** 每 tick 驱动，作用于女仆实体。 */
    fun tick(level: ServerLevel, maid: LivingEntity) {
        val difficulty = level.levelData.difficulty

        if (exhaustionLevel > EXHAUSTION_THRESHOLD) {
            exhaustionLevel -= EXHAUSTION_THRESHOLD
            if (saturationLevel > 0.0f) {
                saturationLevel = maxOf(saturationLevel - 1.0f, 0.0f)
            } else if (difficulty != Difficulty.PEACEFUL) {
                foodLevel = maxOf(foodLevel - 1, 0)
            }
        }

        val naturalRegen = level.gameRules.get(GameRules.NATURAL_HEALTH_REGENERATION)
        val hurt = maid.health > 0.0f && maid.health < maid.maxHealth
        val fastInterval = maxOf(1, kotlin.math.round(10.0 / regenRate).toInt())
        val slowInterval = maxOf(1, kotlin.math.round(80.0 / regenRate).toInt())
        if (naturalRegen && saturationLevel > 0.0f && hurt && foodLevel >= MAX_FOOD) {
            if (++tickTimer >= fastInterval) {
                val healAmount = minOf(saturationLevel, 6.0f)
                maid.heal(healAmount / 6.0f)
                addExhaustion(healAmount)
                tickTimer = 0
            }
        } else if (naturalRegen && foodLevel >= 18 && hurt) {
            if (++tickTimer >= slowInterval) {
                maid.heal(1.0f)
                addExhaustion(6.0f)
                tickTimer = 0
            }
        } else if (foodLevel <= 0) {
            if (++tickTimer >= 80) {
                if (maid.health > 10.0f || difficulty == Difficulty.HARD
                    || (maid.health > 1.0f && difficulty == Difficulty.NORMAL)
                ) {
                    maid.hurtServer(level, maid.damageSources().starve(), 1.0f)
                }
                tickTimer = 0
            }
        } else {
            tickTimer = 0
        }
    }
}
