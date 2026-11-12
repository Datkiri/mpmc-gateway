package org.mpmc.agent.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BedrockGatewayTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `bind must be loopback`() {
        BedrockGateway.validateBind("127.0.0.1:25568")
        BedrockGateway.validateBind("localhost:25568")
        assertThrows<IllegalArgumentException> { BedrockGateway.validateBind("0.0.0.0:25568") }
        assertThrows<IllegalArgumentException> { BedrockGateway.validateBind("192.168.1.10:25568") }
        assertThrows<IllegalArgumentException> { BedrockGateway.validateBind("127.0.0.1") }
    }

    @Test
    fun `target rejects wildcard and bad port`() {
        BedrockGateway.validateTarget("127.0.0.1:19132")
        BedrockGateway.validateTarget("play.example.com:19132")
        assertThrows<IllegalArgumentException> { BedrockGateway.validateTarget("0.0.0.0:19132") }
        assertThrows<IllegalArgumentException> { BedrockGateway.validateTarget("::") }
        assertThrows<IllegalArgumentException> { BedrockGateway.validateTarget("127.0.0.1:70000") }
        assertThrows<IllegalArgumentException> { BedrockGateway.validateTarget("127.0.0.1") }
    }

    @Test
    fun `sha256 matches known vector`() {
        val file = dir.resolve("abc.bin")
        Files.writeString(file, "abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BedrockGateway.sha256(file),
        )
    }

    @Test
    fun `missing or corrupt jar is not installed`() {
        val gateway = BedrockGateway(dir)
        assertFalse(gateway.isInstalled())
        assertTrue(gateway.verify().contains("thiếu"))

        Files.createDirectories(gateway.directory)
        Files.writeString(gateway.jar, "not the real jar")
        assertFalse(gateway.isInstalled())
        assertTrue(gateway.verify().contains("SHA-256"))
    }

    @Test
    fun `local install rejects wrong hash`() {
        val gateway = BedrockGateway(dir)
        val source = dir.resolve("gateway.jar")
        Files.writeString(source, "abc")
        val error = assertThrows<IllegalStateException> {
            gateway.installLocal(source, "00".repeat(32))
        }
        assertTrue(error.message.orEmpty().contains("SHA-256"))
        assertFalse(Files.exists(gateway.jar))
    }

    @Test
    fun `local install copies a matching file`() {
        val gateway = BedrockGateway(dir)
        val source = dir.resolve("gateway.jar")
        Files.writeString(source, "abc")
        gateway.installLocal(source, BedrockGateway.sha256(source))
        assertEquals("abc", Files.readString(gateway.jar))
        assertFalse(gateway.isInstalled())
        assertTrue(gateway.verify().contains("SHA-256 không khớp"))
    }

    @Test
    fun `start requires installed gateway`() {
        val gateway = BedrockGateway(dir)
        val error = assertThrows<IllegalStateException> {
            gateway.start(target = "127.0.0.1:19132")
        }
        assertTrue(error.message.orEmpty().contains("gateway install"))
        gateway.close()
    }

    @Test
    fun `java executable exists`() {
        assertTrue(Files.isRegularFile(Path.of(BedrockGateway.javaExecutable())))
    }
}
