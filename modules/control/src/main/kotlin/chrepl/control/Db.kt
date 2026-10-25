package chrepl.control

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import javax.sql.DataSource

/** Thin JDBC helper: transactions, parameter binding and row mapping. */
class Db(
    val dataSource: DataSource,
) {
    /** Applies the control plane schema (Flyway, `db/migration`). */
    fun migrate() {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    fun <T> tx(block: Tx.() -> T): T =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                Tx(conn).block().also { conn.commit() }
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }

    companion object {
        val json: ObjectMapper =
            jacksonObjectMapper()
                .registerModule(
                    com.fasterxml.jackson.datatype.jsr310
                        .JavaTimeModule(),
                ).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}

class Tx(
    val conn: Connection,
) {
    fun update(
        sql: String,
        vararg args: Any?,
    ): Int = prepare(sql, args).use { it.executeUpdate() }

    /** Runs a statement whose result is ignored (e.g. `SELECT pg_advisory_xact_lock(...)`). */
    fun exec(
        sql: String,
        vararg args: Any?,
    ) {
        prepare(sql, args).use { it.execute() }
    }

    fun <T> query(
        sql: String,
        vararg args: Any?,
        map: (ResultSet) -> T,
    ): List<T> =
        prepare(sql, args).use { st ->
            st.executeQuery().use { rs ->
                val out = mutableListOf<T>()
                while (rs.next()) out += map(rs)
                out
            }
        }

    fun <T> one(
        sql: String,
        vararg args: Any?,
        map: (ResultSet) -> T,
    ): T? = query(sql, *args, map = map).firstOrNull()

    fun long(
        sql: String,
        vararg args: Any?,
    ): Long = one(sql, *args) { it.getLong(1) } ?: 0L

    private fun prepare(
        sql: String,
        args: Array<out Any?>,
    ): PreparedStatement {
        val st = conn.prepareStatement(sql)
        args.forEachIndexed { i, arg ->
            val idx = i + 1
            when (arg) {
                null -> st.setObject(idx, null)
                is Instant -> st.setTimestamp(idx, Timestamp.from(arg))
                is LocalDate -> st.setObject(idx, arg)
                is Json -> st.setObject(idx, arg.text, java.sql.Types.OTHER)
                is StringArray -> st.setArray(idx, conn.createArrayOf("text", arg.values.toTypedArray()))
                is DateArray -> st.setArray(idx, conn.createArrayOf("date", arg.values.map { java.sql.Date.valueOf(it) }.toTypedArray()))
                else -> st.setObject(idx, arg)
            }
        }
        return st
    }
}

/** A `jsonb` parameter. */
class Json(
    val text: String,
) {
    companion object {
        fun of(value: Any): Json = Json(Db.json.writeValueAsString(value))
    }
}

class StringArray(
    val values: List<String>,
)

class DateArray(
    val values: List<LocalDate>,
)

internal fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()

@Suppress("UNCHECKED_CAST")
internal fun ResultSet.strings(column: String): List<String> = (getArray(column)?.array as? Array<String>)?.toList() ?: emptyList()

@Suppress("UNCHECKED_CAST")
internal fun ResultSet.stringsOrNull(column: String): List<String>? = (getArray(column)?.array as? Array<String>)?.toList()

internal fun ResultSet.dates(column: String): List<LocalDate> =
    (getArray(column)?.array as? Array<*>)?.map { (it as java.sql.Date).toLocalDate() } ?: emptyList()
