package org.mpmc.coordinator.it

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mpmc.coordinator.store.Database
import java.sql.DriverManager

class DbSmokeTest {
    @Test
    fun `flyway migrations apply and database is reachable`() {
        Database.connect(PgSupport.jdbcUrl, PgSupport.user, PgSupport.password, poolSize = 2).use { database ->
            database.migrate()
            DriverManager.getConnection(PgSupport.jdbcUrl, PgSupport.user, PgSupport.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "select count(*) from information_schema.tables where table_schema = 'public' and table_name in " +
                            "('agents','rooms','members','join_requests','tickets','relay_grants','audit_log')",
                    ).use { rs ->
                        rs.next()
                        assertEquals(7, rs.getInt(1))
                    }
                }
            }
        }
    }
}
