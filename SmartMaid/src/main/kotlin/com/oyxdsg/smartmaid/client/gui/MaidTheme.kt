package com.oyxdsg.smartmaid.client.gui

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * 女仆菜单视觉令牌（DESIGN_MAID_MENU_N1 §3.8）：对齐桌宠 WBS 绿 主题。
 *
 * 分层靠亮度差不靠描边：弹窗底 [PANEL]（94% 不透明）→ 卡片 [CARD] →
 * 交互反馈用淡色遮罩 [HOVER]/[ACCENT_SOFT]，均不描边。
 */
object MaidTheme {

    // Java 中的 0xFF...... 是 int（有符号）；Kotlin 把高位 hex 推为 Long，故这里写等价的有符号十进制。
    const val BG: Int = -591627                  // 0xFFF6F8F5
    const val CARD: Int = -1                     // 0xFFFFFFFF
    const val HOVER: Int = -1183508              // 0xFFEDF0EC
    const val HOVER_STRONG: Int = -1775134       // 0xFFE4E9E2
    const val TRACK: Int = -1182995              // 0xFFEDF2ED
    const val BORDER: Int = -1906463             // 0xFFE2E8E1
    const val TEXT: Int = -14075088              // 0xFF293B30
    const val TEXT_2: Int = -10918050            // 0xFF59675E
    const val TEXT_3: Int = -9668496             // 0xFF6C7870
    const val ACCENT: Int = -12809138            // 0xFF3C8C4E
    const val ACCENT_SOFT: Int = -3941178        // 0xFFC3DCC6
    const val BTN: Int = -11430560               // 0xFF519560
    const val BTN_HOVER: Int = -12155569         // 0xFF46854F

    /** 世界压暗遮罩：黑 45%（0x73000000）。 */
    const val WORLD_OVERLAY: Int = 0x73000000

    /** 面板底：[BG] 的 94% 不透明（留一点世界透出，保持层次）。 */
    const val PANEL: Int = -252249867            // 0xF0F6F8F5

    /** 面板/卡片底：正常直角填充（不描边，靠亮度差分层）。 */
    @JvmStatic
    fun roundRect(ex: GuiGraphicsExtractor, x1: Int, y1: Int, x2: Int, y2: Int, argb: Int, radius: Int) {
        ex.fill(x1, y1, x2, y2, argb)
    }
}
