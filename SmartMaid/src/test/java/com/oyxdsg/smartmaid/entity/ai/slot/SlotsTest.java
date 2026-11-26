package com.oyxdsg.smartmaid.entity.ai.slot;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link Slots#parse(String)} 的边界回归：女仆背包只有 0-40，越界必须在派发前拒绝。 */
class SlotsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void acceptsValidMaidSlots() {
        for (String spec : new String[]{
                "0", "40", "mainhand", "offhand", "head", "chest", "legs", "feet",
                "inv:0", "inv:40", "inv5"}) {
            assertNotNull(Slots.parse(spec), spec);
        }
    }

    @Test
    void rejectsOutOfRangeMaidSlots() {
        for (String spec : new String[]{
                "-1", "41", "255", "inv:-1", "inv:41", "inv255"}) {
            assertNull(Slots.parse(spec), spec);
        }
    }
}
