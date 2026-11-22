package com.oyxdsg.smartmaid.entity.ai.slot

import com.oyxdsg.smartmaid.entity.SmartMaidEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack

/**
 * 物品槽位抽象（正交 transfer 指令的核心）。
 *
 * <p>女仆相关的所有物品操作统一建模为"从一个槽位把物品移动到另一个槽位"：
 * <code>transfer &lt;from&gt; &lt;to&gt; [count]</code>。</p>
 *
 * <p>槽位类型：</p>
 * <ul>
 *     <li>女仆背包/手持/盔甲：{@code inv:&lt;0-40&gt;}、{@code mainhand}、{@code offhand}、
 *         {@code head/chest/legs/feet}</li>
 *     <li>地面（只作目标）：{@code world:&lt;x&gt;,&lt;y&gt;,&lt;z&gt;}（放置方块 / 丢物品）。
 *         地面→背包的拾取不走 transfer（避免凭空吸物），由 collect/pickup 指令走过去完成</li>
 *     <li>容器（箱子/熔炉等）：{@code container:&lt;x&gt;,&lt;y&gt;,&lt;z&gt;[:&lt;slot|auto&gt;]}</li>
 * </ul>
 */
interface ItemSlot {
    /** 需要女仆靠近才能操作的位置（world/container），null = 女仆自身背包无需移动 */
    fun reachPos(): BlockPos?

    /** 该槽位是否接受此物品（默认接受）。 */
    fun canAccept(maid: SmartMaidEntity, stack: ItemStack): Boolean = true

    /** 目标被不同物品占用时是否允许交换。 */
    fun isSwapAllowed(): Boolean = true

    /** 查看当前槽位物品（不改变状态）。 */
    fun peek(maid: SmartMaidEntity): ItemStack

    /** 从槽位提取最多 count 个物品。 */
    fun extract(maid: SmartMaidEntity, count: Int): ItemStack

    /** 把物品放入槽位，返回未放入的剩余物品。 */
    fun insert(maid: SmartMaidEntity, stack: ItemStack): ItemStack
}
