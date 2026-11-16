package school.server.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import school.server.store.db.SchoolDatabase

class MigrationTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `creates backup before migrating and preserves existing rows`() {
        val dbFile = File(tempDir, "school.db")
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties()).use { seedDriver ->
            seedDriver.execute(null, "CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL);", 0)
            seedDriver.execute(null, "INSERT INTO notes (id, body) VALUES (1, 'hello');", 0)
            seedDriver.execute(null, "PRAGMA user_version = 1;", 0)
        }

        val v2Schema = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version: Long = 2L

            override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit

            override fun migrate(
                driver: SqlDriver,
                oldVersion: Long,
                newVersion: Long,
                vararg callbacks: AfterVersion,
            ): QueryResult.Value<Unit> {
                driver.execute(null, "ALTER TABLE notes ADD COLUMN tag TEXT NOT NULL DEFAULT 'v2';", 0)
                return QueryResult.Unit
            }
        }

        DatabaseFactory.open(dbFile.absolutePath, schema = v2Schema).use { migratedDriver ->
            val backupFile = File(tempDir, "school.v1.bak")
            assertTrue(backupFile.exists(), "Expected backup file ${backupFile.absolutePath}")

            val row = migratedDriver.executeQuery(
                identifier = null,
                sql = "SELECT body, tag FROM notes WHERE id = 1;",
                mapper = { cursor ->
                    assertTrue(cursor.next().value)
                    QueryResult.Value("${cursor.getString(0)}:${cursor.getString(1)}")
                },
                parameters = 0,
            ).value
            assertEquals("hello:v2", row)
        }
    }

    @Test
    fun `failed migration leaves original file and backup intact`() {
        val dbFile = File(tempDir, "school.db")
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties()).use { seedDriver ->
            seedDriver.execute(null, "CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL);", 0)
            seedDriver.execute(null, "INSERT INTO notes (id, body) VALUES (1, 'safe');", 0)
            seedDriver.execute(null, "PRAGMA user_version = 1;", 0)
        }

        val failingSchema = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version: Long = 2L

            override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit

            override fun migrate(
                driver: SqlDriver,
                oldVersion: Long,
                newVersion: Long,
                vararg callbacks: AfterVersion,
            ): QueryResult.Value<Unit> {
                driver.execute(null, "INSERT INTO notes (id, body) VALUES (2, 'partial');", 0)
                throw IllegalStateException("Simulated migration failure")
            }
        }

        assertFailsWith<StorageUnavailableException> {
            DatabaseFactory.open(dbFile.absolutePath, schema = failingSchema)
        }

        val backupFile = File(tempDir, "school.v1.bak")
        assertTrue(backupFile.exists())

        JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties()).use { checkDriver ->
            val count = checkDriver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM notes;",
                mapper = { cursor ->
                    assertTrue(cursor.next().value)
                    QueryResult.Value(cursor.getLong(0))
                },
                parameters = 0,
            ).value
            assertEquals(1L, count)
        }
    }

    @Test
    fun `schema version matches number of sqm migration files plus one`() {
        val sqldelightDir = File("src/main/sqldelight/school/server/store/db")
        val sqmCount = sqldelightDir.listFiles { file -> file.extension == "sqm" }?.size ?: 0
        assertEquals(
            expected = sqmCount + 1L,
            actual = SchoolDatabase.Schema.version,
            message = "Every schema bump requires a .sqm migration file",
        )
    }
}
