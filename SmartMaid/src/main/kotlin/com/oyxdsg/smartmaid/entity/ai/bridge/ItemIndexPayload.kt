package com.oyxdsg.smartmaid.entity.ai.bridge

import com.google.gson.JsonObject
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import java.util.TreeMap

/**
 * 物品索引下发（供桌宠 NLU 做物品名模糊匹配）。
 *
 * <p>把「当前语言的物品显示名 → {@code minecraft:id}」的完整映射表推给桌宠，桌宠侧就能
 * 在本地做中文/拼音模糊匹配（「帮我合成一把稿子」→「镐子」→
 * {@code minecraft:wooden_pickaxe}），再把**权威 id** 发回来执行。</p>
 *
 * <p>为什么由模组下发、而不是桌宠自带词典：</p>
 * <ul>
 *     <li>覆盖 **mod 物品**——词典随整合包自动变化，桌宠不用维护</li>
 *     <li>名称跟随**客户端当前语言**，中文包/英文包都可匹配</li>
 *     <li>桌宠不必打包 {@code zh_cn.json}，也就不存在版本同步问题</li>
 * </ul>
 *
 * <p>名称取自客户端 {@code I18n}（语言资源在客户端）。专用服务端没有语言资源，
 * 此时 {@link #build()} 返回 {@code null}，桌宠会自动降级为「认得出名字但执行不了」，
 * **不会误执行**。</p>
 *
 * <p>结果按连接缓存一次（物品表在运行期不变）。</p>
 */
object ItemIndexPayload {
    /** 单次下发的名字上限（防极端整合包把一条 WS 消息撑爆） */
    private const val MAX_ENTRIES = 4096
    /** 过长的显示名基本不是物品名（如自定义名/tooltip 类），跳过 */
    private const val MAX_NAME_LEN = 16

    @Volatile
    private var cached: JsonObject? = null

    @JvmStatic
    fun build(): JsonObject? {
        val hit = cached
        if (hit != null) {
            return hit
        }
        val map = TreeMap<String, String>()
        try {
            for (item: Item in BuiltInRegistries.ITEM.stream().toList()) {
                val id: String
                try {
                    id = BuiltInRegistries.ITEM.getKey(item).toString()
                } catch (_: Throwable) {
                    continue
                }
                val desc = item.descriptionId
                val name: String?
                try {
                    name = net.minecraft.client.resources.language.I18n.get(desc)
                } catch (_: Throwable) {
                    // 专用服务端：没有客户端语言资源，无法提供中文名
                    return null
                }
                if (name == null || name.isEmpty()) {
                    continue
                }
                if (name == desc) {
                    continue // 未翻译（返回了 key 本身）
                }
                val trimmed = name.trim()
                if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LEN) {
                    continue
                }
                map.putIfAbsent(trimmed, id)
                if (map.size >= MAX_ENTRIES) {
                    break
                }
            }
        } catch (_: Throwable) {
            return null
        }
        if (map.isEmpty()) {
            return null
        }
        val items = JsonObject()
        for ((key, value) in map) {
            items.addProperty(key, value)
        }
        val msg = JsonObject()
        msg.addProperty("type", "item_index")
        msg.addProperty("count", map.size)
        msg.add("items", items)
        cached = msg
        return msg
    }

    /** 语言/资源包切换后调用（当前未用，留作扩展点） */
    @JvmStatic
    fun invalidate() {
        cached = null
    }
}
