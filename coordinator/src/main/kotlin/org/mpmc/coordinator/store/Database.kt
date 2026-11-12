package org.mpmc.coordinator.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway

class Database private constructor(val dataSource: HikariDataSource) : AutoCloseable {
    fun migrate() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    override fun close() {
        dataSource.close()
    }

    companion object {
        fun connect(
            jdbcUrl: String,
            user: String,
            password: String,
            poolSize: Int = 10,
        ): Database {
            val config = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                username = user
                this.password = password
                maximumPoolSize = poolSize
                minimumIdle = 1
                connectionTimeout = 10_000
                validationTimeout = 5_000
                poolName = "mpmc-pg"
            }
            return Database(HikariDataSource(config))
        }
    }
}
