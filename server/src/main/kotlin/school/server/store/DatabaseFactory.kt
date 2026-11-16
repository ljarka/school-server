package school.server.store

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import org.slf4j.LoggerFactory
import school.server.store.db.SchoolDatabase

class StorageUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun open(dbPath: String, schema: SqlSchema<QueryResult.Value<Unit>> = SchoolDatabase.Schema): SqlDriver {
        val dbFile = File(dbPath)
        dbFile.parentFile?.mkdirs()

        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            properties = Properties(),
        )

        try {
            executePragma(driver, "PRAGMA foreign_keys = ON;")
            executePragma(driver, "PRAGMA journal_mode = WAL;")

            val currentVersion = readUserVersion(driver)
            val targetVersion = schema.version

            val transacter = object : TransacterImpl(driver) {}

            when {
                currentVersion == 0L && !hasExistingTables(driver) -> {
                    transacter.transaction {
                        schema.create(driver)
                        executePragma(driver, "PRAGMA user_version = $targetVersion;")
                    }
                }

                currentVersion < targetVersion -> {
                    if (dbFile.exists()) {
                        val backupFile = File(
                            dbFile.parentFile,
                            "${dbFile.nameWithoutExtension}.v$currentVersion.bak",
                        )
                        Files.copy(
                            dbFile.toPath(),
                            backupFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                        logger.info("Backed up database v{} to {}", currentVersion, backupFile.name)
                    }

                    transacter.transaction {
                        schema.migrate(driver, currentVersion, targetVersion)
                        executePragma(driver, "PRAGMA user_version = $targetVersion;")
                    }
                }

                currentVersion > targetVersion -> {
                    throw StorageUnavailableException(
                        "Database version $currentVersion is newer than supported schema $targetVersion",
                    )
                }
            }
        } catch (e: StorageUnavailableException) {
            runCatching { driver.close() }
            throw e
        } catch (e: Throwable) {
            runCatching { driver.close() }
            logger.error("Database initialization or migration failed", e)
            throw StorageUnavailableException("Database initialization failed: ${e.message}", e)
        }

        return driver
    }

    private fun executePragma(driver: SqlDriver, sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun readUserVersion(driver: SqlDriver): Long = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version;",
        mapper = { cursor ->
            val hasRow = cursor.next().value
            QueryResult.Value(if (hasRow) cursor.getLong(0) ?: 0L else 0L)
        },
        parameters = 0,
    ).value

    private fun hasExistingTables(driver: SqlDriver): Boolean = driver.executeQuery(
        identifier = null,
        sql = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%';",
        mapper = { cursor ->
            val hasRow = cursor.next().value
            QueryResult.Value(if (hasRow) (cursor.getLong(0) ?: 0L) > 0L else false)
        },
        parameters = 0,
    ).value
}
