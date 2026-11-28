package com.oyxdsg.smartmaid.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaidMenuPolicyTest {
    @Test
    void allowsOnlyUiQuickCommands() {
        for (String cmd : new String[]{"mine", "guard", "collect", "eat", "attack", "move", "farm", "cheststore"}) {
            assertTrue(MaidMenuPolicy.isAllowedQuickCommand(cmd), cmd);
        }
        for (String cmd : new String[]{"script", "build", "craft", "smelt", "transfer", "chestopen", "ai", "", null}) {
            assertFalse(MaidMenuPolicy.isAllowedQuickCommand(cmd), String.valueOf(cmd));
        }
    }

    @Test
    void allowsOnlyMenuQueueOps() {
        for (String op : new String[]{"query", "cancelCurrent", "clear", "stop", "promote", "moveUp", "remove", "stopGroup"}) {
            assertTrue(MaidMenuPolicy.isAllowedQueueOp(op), op);
        }
        for (String op : new String[]{"enqueue", "command", "script", "", null}) {
            assertFalse(MaidMenuPolicy.isAllowedQueueOp(op), String.valueOf(op));
        }
    }

    @Test
    void classifiesQuickCommandsSeparatelyFromQueueManagement() {
        assertFalse(MaidMenuPolicy.classify("enqueue", "unlisted").isAllowed());
        assertFalse(MaidMenuPolicy.classify("enqueue", "").isAllowed());
        assertFalse(MaidMenuPolicy.classify("unknown", "mine").isAllowed());
        assertTrue(MaidMenuPolicy.classify("enqueue", "mine").isAllowed());
        assertTrue(MaidMenuPolicy.classify("enqueue", "mine").isQueue());
        assertTrue(MaidMenuPolicy.classify("query", null).isAllowed());
        assertFalse(MaidMenuPolicy.classify("query", null).isQueue());
    }
}
