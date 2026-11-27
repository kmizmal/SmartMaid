package com.oyxdsg.smartmaid.entity.ai.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaidAIBridgeLimitsTest {
    @Test
    void clampsKnownIntegerParams() {
        assertEquals(32, MaidAIBridge.clampParam("range", 999));
        assertEquals(1, MaidAIBridge.clampParam("range", -5));
        assertEquals(32, MaidAIBridge.clampParam("max_range", 999));
        assertEquals(1, MaidAIBridge.clampParam("expand", 0));
        assertEquals(64, MaidAIBridge.clampParam("count", 1_000_000_000));
        assertEquals(32, MaidAIBridge.clampParam("height", 999));
        assertEquals(-1, MaidAIBridge.clampParam("slot", -1));
        assertEquals(53, MaidAIBridge.clampParam("slot", 999));
        assertEquals(64, MaidAIBridge.clampParam("to", 999));
        assertEquals(999, MaidAIBridge.clampParam("unbounded", 999));
    }

    @Test
    void intParamClampsBeforeNarrowingAndKeepsFallbacks() {
        JsonObject params = new JsonObject();
        params.addProperty("count", new BigInteger("4294967297"));
        assertEquals(64, MaidAIBridge.intParam(params, "count", 1));
        params.addProperty("count", new BigInteger("-4294967297"));
        assertEquals(1, MaidAIBridge.intParam(params, "count", 7));
        params.addProperty("count", 4.5);
        assertEquals(4, MaidAIBridge.intParam(params, "count", 7));
        params.addProperty("count", "18");
        assertEquals(7, MaidAIBridge.intParam(params, "count", 7));
        params.addProperty("count", Double.POSITIVE_INFINITY);
        assertEquals(7, MaidAIBridge.intParam(params, "count", 7));
    }
}
