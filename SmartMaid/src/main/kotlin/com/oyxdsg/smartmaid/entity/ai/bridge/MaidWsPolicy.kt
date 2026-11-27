package com.oyxdsg.smartmaid.entity.ai.bridge

import com.google.gson.JsonObject
import java.net.URI
import java.net.URISyntaxException

object MaidWsPolicy {
    @JvmStatic
    fun allowsBeforeHandshake(type: String): Boolean = type == "hello_ack"

    @JvmStatic
    fun isHandshakeAccepted(msg: JsonObject): Boolean = explicitTrue(msg, "ok")

    internal fun explicitTrue(obj: JsonObject, key: String): Boolean {
        val value = obj.get(key) ?: return false
        return value.isJsonPrimitive && value.asJsonPrimitive.isBoolean && value.asBoolean
    }

    @JvmStatic
    fun validateConfig(url: String, token: String, allowInsecure: Boolean): String? {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return "ws.url 不是合法 URI"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "ws" && scheme != "wss") return "ws.url 只支持 ws:// 或 wss://"
        if (token.isBlank()) return "必须配置非空 ws.token"
        val host = uri.host?.lowercase() ?: return "ws.url 缺少主机名"
        if (uri.port !in -1..65535 || uri.port == 0 || uri.rawFragment != null || uri.rawUserInfo != null) {
            return "ws.url 包含无效端口、片段或用户信息"
        }
        if (scheme == "wss" || host in setOf("localhost", "127.0.0.1", "::1", "[::1]") || allowInsecure) return null
        return "非回环地址必须使用 wss://；如确需明文请显式设置 ws.allowInsecure=true"
    }
}
