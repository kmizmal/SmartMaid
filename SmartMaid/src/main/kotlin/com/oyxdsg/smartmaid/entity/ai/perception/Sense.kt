package com.oyxdsg.smartmaid.entity.ai.perception

import com.oyxdsg.smartmaid.entity.SmartMaidEntity

/** 感知通道接口：负责采集一类感知信息并写入快照区块。 */
interface Sense {
    fun intervalTicks(): Int

    fun sectionName(): String

    fun collect(maid: SmartMaidEntity, data: MaidPerception)
}
