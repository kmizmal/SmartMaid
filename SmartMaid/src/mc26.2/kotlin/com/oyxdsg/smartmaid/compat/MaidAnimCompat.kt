package com.oyxdsg.smartmaid.compat

/**
 * 动作引擎（mocha / molang 表达式求值器）的**版本差异隔离层** —— 26.2 实现。
 *
 * PAL 在 1.2.7 里换掉了内嵌的表达式引擎：包名从 `team.unnamed.mocha`
 * （jar-in-jar 的 mochafloats 5.0.0）换成 `org.redlance.mocha.runtime`，
 * 类名 `MochaEngine` 也变成 `MolangInterpreter`。工厂方法 `create(T)`
 * 语义不变，所以只剩包名 + 类名两处差异。
 */
object MaidAnimCompat {

    /**
     * 为动画控制器创建一个表达式引擎。
     *
     * @param entity 引擎绑定的持有者对象（PAL 传入 `AnimationController`）
     * @return 新建的 mocha 引擎
     */
    @JvmStatic
    fun <T> createEngine(entity: T): team.unnamed.mocha.MochaEngine<T> =
        team.unnamed.mocha.MochaEngine.create(entity)
}
