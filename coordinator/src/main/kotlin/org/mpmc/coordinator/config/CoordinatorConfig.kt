package org.mpmc.coordinator.config

import java.nio.file.Path

data class CoordinatorConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val databaseUrl: String,
    val databaseUser: String = "mpmc",
    val databasePassword: String = "",
    val signingKeyPath: Path,
    val relayCallbackToken: String,
    val relayUrls: List<String> = emptyList(),
    val ticketTtlSeconds: Long = 120,
    val joinRequestTtlSeconds: Long = 300,
    val accessTokenTtlSeconds: Long = 900,
    val challengeTtlSeconds: Long = 60,
    val roomHeartbeatTimeoutSeconds: Long = 60,
    val memberLastSeenSeconds: Long = 120,
    val maxBodyBytes: Long = 16 * 1024,
    val maxMembersLimit: Int = 64,
    val maxTtlMinutes: Int = 1440,
    val relayGrantSeconds: Long = 3600,
    val sweeperIntervalSeconds: Long = 30,
) {
    init {
        require(port in 1..65535) { "port out of range: $port" }
        require(databaseUrl.startsWith("jdbc:postgresql:")) { "databaseUrl must be a postgresql jdbc url" }
        require(relayCallbackToken.isNotBlank()) { "relayCallbackToken must not be blank" }
        require(ticketTtlSeconds > 0) { "ticketTtlSeconds must be positive" }
        require(joinRequestTtlSeconds > 0) { "joinRequestTtlSeconds must be positive" }
        require(accessTokenTtlSeconds > 0) { "accessTokenTtlSeconds must be positive" }
        require(challengeTtlSeconds > 0) { "challengeTtlSeconds must be positive" }
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive" }
        require(sweeperIntervalSeconds > 0) { "sweeperIntervalSeconds must be positive" }
    }

    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): CoordinatorConfig {
            fun required(name: String): String =
                env(name)?.takeIf { it.isNotBlank() } ?: error("missing required environment variable $name")

            return CoordinatorConfig(
                host = env("MPMC_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
                port = env("MPMC_PORT")?.toIntOrNull() ?: 8080,
                databaseUrl = required("DATABASE_URL"),
                databaseUser = env("DATABASE_USER")?.takeIf { it.isNotBlank() } ?: "mpmc",
                databasePassword = env("DATABASE_PASSWORD") ?: "",
                signingKeyPath = Path.of(required("SIGNING_KEY_PATH")),
                relayCallbackToken = required("RELAY_CALLBACK_TOKEN"),
                relayUrls = env("MPMC_RELAY_URLS")
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?: emptyList(),
            )
        }
    }
}
