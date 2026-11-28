package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.google.gson.JsonObject

/** 任务显示名（服务端由 cmd + params 生成中文摘要，供队列快照/菜单展示）。 */
object MaidTaskNames {
    @JvmStatic
    fun display(cmd: String, params: JsonObject?): String {
        val p = params ?: JsonObject()
        return when (cmd) {
            "mine" -> "挖矿 ${intp(p, "count", 8)} 块"
            "guard" -> "护卫 半径${intp(p, "range", 16)}"
            "attack" -> "攻击" + if (p.has("target") && !p["target"].isJsonNull) " ${shortId(p["target"].asString)}" else " 最近敌对"
            "farm" -> "耕作"
            "build" -> "建造"
            "collect" -> "收集"
            "craft" -> "制作 ${shortId(str(p, "item", "?"))}"
            "smelt" -> "烧炼 ${shortId(str(p, "item", "?"))}"
            "eat" -> if (p.has("item")) "进食 ${shortId(str(p, "item", ""))}" else "进食"
            "feed" -> "喂食主人"
            "move" -> "移动"
            "look" -> "看向"
            "break" -> "破坏方块"
            "place" -> "放置方块"
            "use" -> "使用"
            "chestopen" -> "打开箱子"
            "chestput", "cheststore" -> "存入箱子"
            "chesttake" -> "取出箱子"
            "transfer" -> "转移物品"
            "equip" -> "装备"
            "store" -> "收起物品"
            "drop" -> "丢出物品"
            "pickup" -> "拾取"
            "sit" -> "坐下/站起"
            "script" -> "脚本任务"
            else -> cmd
        }
    }

    private fun intp(p: JsonObject, key: String, default: Int): Int =
        if (p.has(key) && p[key].isJsonPrimitive && p[key].asJsonPrimitive.isNumber) p[key].asInt else default

    private fun str(p: JsonObject, key: String, default: String): String =
        if (p.has(key) && !p[key].isJsonNull) p[key].asString else default

    private fun shortId(id: String): String {
        val index = id.indexOf(':')
        return if (index >= 0) id.substring(index + 1) else id
    }
}
