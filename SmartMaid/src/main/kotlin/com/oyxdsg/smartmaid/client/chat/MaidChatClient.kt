package com.oyxdsg.smartmaid.client.chat

/** 客户端对话模式，仅拦截普通聊天，不拦截指令。 */
object MaidChatClient {
    private var chatMode = false
    @JvmStatic fun isChatMode(): Boolean = chatMode
    @JvmStatic fun toggle(): Boolean {
        chatMode = !chatMode
        return chatMode
    }
}
