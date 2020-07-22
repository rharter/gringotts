package xchange

import com.mchange.v2.c3p0.DataSources
import com.squareup.sqldelight.ColumnAdapter
import com.squareup.sqldelight.TransacterImpl
import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.sqlite.driver.asJdbcDriver
import io.ktor.application.Application
import io.ktor.application.ApplicationStopped
import io.ktor.application.log
import org.slf4j.Logger
import xchange.db.Database
import xchange.db.Database.Companion.Schema
import xchange.db.Rates
import java.io.File
import java.time.LocalDate

fun Database(app: Application): Database {
  val dbConfig = app.environment.config.config("database")
  var connectionUrl = dbConfig.property("connection").getString()
  if (connectionUrl.contains("jdbc:h2:file:")) {
    val dbFile = File(connectionUrl.removePrefix("jdbc:h2:file:")).absoluteFile
    if (!dbFile.parentFile.exists()) {
      dbFile.parentFile.mkdirs()
    }
    connectionUrl = "jdbc:h2:file:${dbFile.absolutePath}"
  }

  val poolSize = dbConfig.propertyOrNull("poolSize")?.getString()?.toInt()

  var dataSource = DataSources.unpooledDataSource(connectionUrl)
  if (poolSize != null && poolSize > 1) {
    dataSource = DataSources.pooledDataSource(dataSource, mapOf("maxPoolSize" to poolSize))
  }

  val driver = dataSource.asJdbcDriver()
  val db = Database(
    driver,
    Rates.Adapter(LocalDateColumnAdapter)
  )
  app.environment.monitor.subscribe(ApplicationStopped) { driver.close() }

  driver.migrate(Schema, app.log)

  app.log.debug("Connected to database at ${dataSource.connection.metaData.url}")

  return db
}

// TODO If SqlDriver gets updated to implement Transacter then we don't need this silly wrapper.
private class SqlDriverTransacter(driver: SqlDriver) : TransacterImpl(driver)

private fun SqlDriver.migrate(schema: SqlDriver.Schema, logger: Logger? = null) =
  SqlDriverTransacter(this).transaction {
    var needsMetaTable = false
    val version = try {
      executeQuery(null, "SELECT value FROM __sqldelight__ WHERE name = 'schema_version'", 0).use {
        (if (it.next()) it.getLong(0)?.toInt() else 0) ?: 0
      }
    } catch (e: Exception) {
      needsMetaTable = true
      0
    }

    if (version < schema.version) {
      logger?.debug("Migrating database from schema version $version to version ${schema.version}")

      execute(null, "SET REFERENTIAL_INTEGRITY FALSE", 0)
      if (version == 0) schema.create(this@migrate) else schema.migrate(this@migrate, version, schema.version)
      execute(null, "SET REFERENTIAL_INTEGRITY TRUE", 0)

      if (needsMetaTable) {
        execute(null, "CREATE TABLE __sqldelight__(name VARCHAR NOT NULL PRIMARY KEY, value VARCHAR)", 0)
      }

      if (version == 0) {
        execute(null, "INSERT INTO __sqldelight__(name, value) VALUES('schema_version', ${Schema.version})", 0)
      } else {
        execute(null, "UPDATE __sqldelight__ SET value='${Schema.version}' WHERE name='schema_version'", 0)
      }
    }
  }

private object LocalDateColumnAdapter :
  ColumnAdapter<LocalDate, String> {
  override fun decode(databaseValue: String): LocalDate = LocalDate.parse(databaseValue)
  override fun encode(value: LocalDate) = value.toString()
}
