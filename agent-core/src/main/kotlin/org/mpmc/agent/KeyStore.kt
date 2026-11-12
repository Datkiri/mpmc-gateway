package org.mpmc.agent

import org.mpmc.protocol.Digest
import org.mpmc.protocol.Ed25519
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.PrivateKey

class AgentIdentity(
    val privateKey: PrivateKey,
    val publicRaw: ByteArray,
) {
    val endpointId: String = Digest.hex(publicRaw)

    /** 32-byte Ed25519 seed (iroh expects the raw secret key). */
    val seed: ByteArray
        get() {
            val pkcs8 = privateKey.encoded
            require(pkcs8.size == 48) { "unexpected PKCS#8 length: ${pkcs8.size}" }
            return pkcs8.copyOfRange(16, 48)
        }

    fun sign(message: ByteArray): ByteArray = Ed25519.sign(privateKey, message)
}

object KeyStore {
    const val KEY_FILE = "agent.key"

    fun loadOrCreate(dataDir: Path): AgentIdentity {
        Files.createDirectories(dataDir)
        val path = dataDir.resolve(KEY_FILE)
        if (Files.exists(path)) return load(path)
        val generator = KeyPairGenerator.getInstance("Ed25519")
        val pair = generator.generateKeyPair()
        val pkcs8 = pair.private.encoded
        val raw = Ed25519.rawFromPublic(pair.public)
        val bytes = pkcs8 + raw
        val tmp = dataDir.resolve("$KEY_FILE.tmp")
        Files.write(tmp, bytes)
        restrictPermissions(tmp)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return AgentIdentity(pair.private, raw)
    }

    fun load(path: Path): AgentIdentity {
        val bytes = Files.readAllBytes(path)
        require(bytes.size == 80) { "agent.key không hợp lệ (kích thước ${bytes.size})" }
        val privateBytes = bytes.copyOfRange(0, 48)
        val publicRaw = bytes.copyOfRange(48, 80)
        return AgentIdentity(Ed25519.privateFromPkcs8(privateBytes), publicRaw)
    }

    private fun restrictPermissions(path: Path) {
        runCatching {
            val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            Files.setPosixFilePermissions(path, perms)
        }
    }
}
