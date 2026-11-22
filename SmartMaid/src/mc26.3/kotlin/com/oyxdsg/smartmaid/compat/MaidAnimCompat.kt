package com.oyxdsg.smartmaid.compat

/**
 * 动作引擎（mocha / molang 表达式求值器）的**版本差异隔离层** —— 26.3 实现。
 *
 * PAL 1.2.7 把内嵌表达式引擎从 `team.unnamed.mocha.MochaEngine`（mochafloats 5.0.0）
 * 换成了 `org.redlance.mocha.runtime.MolangInterpreter`（runtime/parser/lexer 6.0.1）。
 */
object MaidAnimCompat {

    /**
     * 为动画控制器创建一个表达式引擎。
     *
     * @param entity 引擎绑定的持有者对象（PAL 传入 `AnimationController`）
     */
    @JvmStatic
    fun <T> createEngine(entity: T): org.redlance.mocha.runtime.MolangInterpreter<T> =
        org.redlance.mocha.runtime.MolangInterpreter.create(entity)
}
