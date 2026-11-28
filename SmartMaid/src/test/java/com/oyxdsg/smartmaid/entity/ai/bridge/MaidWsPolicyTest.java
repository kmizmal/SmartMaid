package com.oyxdsg.smartmaid.entity.ai.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaidWsPolicyTest {
    @Test
    void acceptsOnlyExplicitBooleanAckAndBlocksOtherPreHandshakeMessages() {
        assertTrue(MaidWsPolicy.allowsBeforeHandshake("hello_ack"));
        for (var type : new String[]{"command", "speak", "chat_reply", "animation", "ping", "pong"}) {
            assertFalse(MaidWsPolicy.allowsBeforeHandshake(type), type);
        }
        for (var text : new String[]{"{}", "{\"ok\":false}", "{\"ok\":null}", "{\"ok\":\"true\"}", "{\"ok\":1}"}) {
            assertFalse(MaidWsPolicy.isHandshakeAccepted(JsonParser.parseString(text).getAsJsonObject()), text);
        }
        assertTrue(MaidWsPolicy.isHandshakeAccepted(JsonParser.parseString("{\"ok\":true}").getAsJsonObject()));
    }

    @Test
    void requiresTokenAndExplicitRemotePlaintextOptIn() {
        String local = "ws://127.0.0.1:21420";
        String remote = "ws://192.168.1.10:21420";
        assertNotNull(MaidWsPolicy.validateConfig(local, "", false));
        assertNotNull(MaidWsPolicy.validateConfig(local, "  ", true));
        assertNotNull(MaidWsPolicy.validateConfig("ws://", "test", true));
        assertNotNull(MaidWsPolicy.validateConfig("ws://localhost:65536", "test", true));
        assertNull(MaidWsPolicy.validateConfig(local, "test", false));
        assertNull(MaidWsPolicy.validateConfig("ws://[::1]:21420", "test", false));
        assertNotNull(MaidWsPolicy.validateConfig(remote, "test", false));
        assertNull(MaidWsPolicy.validateConfig(remote, "test", true));
        assertNotNull(MaidWsPolicy.validateConfig("http://example.com/ws", "test", true));
        assertNull(MaidWsPolicy.validateConfig("wss://example.com/ws", "test", false));
    }
}
