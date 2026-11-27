package com.oyxdsg.smartmaid.entity.ai.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScriptTaskLimitsTest {
    private static JsonArray steps(int size) {
        JsonArray steps = new JsonArray();
        for (int i = 0; i < size; i++) {
            JsonObject step = new JsonObject();
            step.addProperty("cmd", "status");
            steps.add(step);
        }
        return steps;
    }

    private static JsonObject script(JsonArray steps) {
        JsonObject script = new JsonObject();
        script.add("steps", steps);
        return script;
    }

    private static JsonObject loop(JsonObject script) {
        JsonObject loop = new JsonObject();
        loop.add("body", steps(1));
        script.getAsJsonArray("steps").get(0).getAsJsonObject().add("loop", loop);
        return loop;
    }

    @Test
    void acceptsWithinLimits() {
        JsonObject script = script(steps(512));
        script.addProperty("max_steps", 512);
        loop(script).addProperty("max_iter", 256);
        assertNull(ScriptTask.validate(script));
    }

    @Test
    void rejectsOversizedListsAndDepth() {
        assertNotNull(ScriptTask.validate(script(steps(513))));
        assertNotNull(ScriptTask.validate(script(steps(0))));
        JsonObject script = script(steps(1));
        JsonObject root = script.getAsJsonArray("steps").get(0).getAsJsonObject();
        root.add("then", steps(513));
        assertNotNull(ScriptTask.validate(script));
        root.remove("then");
        JsonObject current = root;
        for (int i = 0; i < 65; i++) {
            JsonArray branch = steps(1);
            current.add("else", branch);
            current = branch.get(0).getAsJsonObject();
        }
        assertNotNull(ScriptTask.validate(script));
    }

    @Test
    void rejectsMalformedOutOfRangeAndNonIntegralLimits() {
        JsonObject script = script(steps(1));
        for (Number bad : new Number[]{0, 513, 1.5, new BigInteger("4294967297"), Double.NaN}) {
            script.addProperty("max_steps", bad);
            assertNotNull(ScriptTask.validate(script));
        }
        script.addProperty("max_steps", "64");
        assertNotNull(ScriptTask.validate(script));
        script.remove("max_steps");
        JsonObject loop = loop(script);
        loop.addProperty("max_iter", 257);
        assertNotNull(ScriptTask.validate(script));
        loop.addProperty("max_iter", "abc");
        assertNotNull(ScriptTask.validate(script));
        assertDoesNotThrow(() -> new ScriptTask(script));
    }
}
