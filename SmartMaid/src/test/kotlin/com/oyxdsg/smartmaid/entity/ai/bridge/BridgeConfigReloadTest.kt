package com.oyxdsg.smartmaid.entity.ai.bridge

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BridgeConfigReloadTest {
    @Test
    fun `replacing or removing ws settings invalidates previous transport settings`(@TempDir dir: Path) {
        val file = dir.resolve("bridge.json")
        Files.writeString(file, """{"ws":{"enabled":true,"token":"local-fixture"}}""")
        val first = BridgeConfig.readWsConfig(file).transport
        assertTrue(first.enabled)
        assertNull(first.rejection())

        Files.writeString(file, """{"ws":{"enabled":true,"token":"local-replacement"}}""")
        val replaced = BridgeConfig.readWsConfig(file).transport
        assertNotEquals(first, replaced)
        assertNull(replaced.rejection())

        Files.writeString(file, """{"ws":{"enabled":true}}""")
        assertNotNull(BridgeConfig.readWsConfig(file).transport.rejection())

        for (invalid in listOf("{}", "{", """{"ws":{"enabled":"true"}}""")) {
            Files.writeString(file, invalid)
            assertFalse(BridgeConfig.readWsConfig(file).transport.enabled)
        }
        Files.delete(file)
        assertFalse(BridgeConfig.readWsConfig(file).transport.enabled)
        assertFalse(Files.exists(file))
    }
}
