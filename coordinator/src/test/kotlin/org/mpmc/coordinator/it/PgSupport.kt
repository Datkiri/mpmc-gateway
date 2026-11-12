package org.mpmc.coordinator.it

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object PgSupport {
    private val logger = LoggerFactory.getLogger(PgSupport::class.java)

    private val container: PostgreSQLContainer<*>? = startDockerOrNull()
    private val embedded: EmbeddedPostgres? = if (container == null) startEmbeddedOrNull() else null
    private var embeddedFailure: Throwable? = null

    val usingDocker: Boolean get() = container != null

    val jdbcUrl: String
        get() = container?.jdbcUrl ?: embedded?.getJdbcUrl("postgres", "postgres")
            ?: error("no PostgreSQL available for integration tests: ${embeddedFailure?.message}")

    val user: String get() = container?.username ?: "postgres"
    val password: String get() = container?.password ?: "postgres"

    init {
        if (container != null) {
            logger.warn("integration_tests_using_testcontainers jdbcUrl=${container.jdbcUrl}")
        } else if (embedded != null) {
            logger.warn("integration_tests_using_embedded_postgres jdbcUrl=${embedded.getJdbcUrl("postgres", "postgres")}")
        } else {
            logger.error("integration_tests_no_postgres available")
        }
    }

    private fun startDockerOrNull(): PostgreSQLContainer<*>? = try {
        if (!DockerClientFactory.instance().isDockerAvailable) {
            null
        } else {
            PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).apply { start() }
        }
    } catch (t: Throwable) {
        logger.warn("docker_unavailable falling_back_to_embedded_postgres: ${t::class.simpleName}: ${t.message}")
        null
    }

    private fun startEmbeddedOrNull(): EmbeddedPostgres? = try {
        EmbeddedPostgres.builder().start()
    } catch (t: Throwable) {
        embeddedFailure = t
        logger.warn("embedded_postgres_failed: ${t::class.simpleName}: ${t.message}")
        null
    }
}
