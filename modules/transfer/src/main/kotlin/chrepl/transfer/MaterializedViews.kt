package chrepl.transfer

import chrepl.clickhouse.ClickHouseClient
import chrepl.clickhouse.Sql
import chrepl.config.model.Target

/** A materialized view that reads from a copied table and writes `TO` another table. */
data class ViewInfo(
    val database: String,
    val name: String,
    val toDatabase: String,
    val toTable: String,
    val columns: List<String>,
    val asSelect: String,
    val createStatement: String,
)

/**
 * `mv_mode: base_only` (SPEC §5.2): the base table is copied and the views that read from it are
 * re-created on the target, with their `TO` tables. Partition replacement does not trigger views,
 * so after each copy the view targets are recomputed on the target from the (masked) base table.
 * View targets are never copied from DR, so anything derived from masked columns stays masked.
 * Views with implicit inner storage (no `TO`) are not supported and are skipped.
 */
object MaterializedViews {
    private val TO = Regex("""\bTO\s+(`[^`]+`|\w+)\.(`[^`]+`|\w+)""")

    /** Views that read from [database].[table] on [client]'s server. */
    fun dependents(
        client: ClickHouseClient,
        database: String,
        table: String,
    ): List<ViewInfo> {
        val deps =
            client
                .queryJson(
                    "SELECT dependencies_database AS d, dependencies_table AS t FROM system.tables WHERE database = {db:String} AND name = {t:String}",
                    mapOf("param_db" to database, "param_t" to table),
                ).singleOrNull() ?: return emptyList()
        val names = deps["d"].map { it.textValue() }.zip(deps["t"].map { it.textValue() })
        return names.mapNotNull { (db, name) ->
            val row =
                client
                    .queryJson(
                        "SELECT engine, create_table_query AS q, as_select AS s FROM system.tables WHERE database = {db:String} AND name = {t:String}",
                        mapOf("param_db" to db, "param_t" to name),
                    ).singleOrNull() ?: return@mapNotNull null
            if (row["engine"].textValue() != "MaterializedView") return@mapNotNull null
            val create = row["q"].textValue()
            val to = TO.find(create) ?: return@mapNotNull null
            val columns =
                client
                    .queryJson(
                        "SELECT name FROM system.columns WHERE database = {db:String} AND table = {t:String} ORDER BY position",
                        mapOf("param_db" to db, "param_t" to name),
                    ).map { it["name"].textValue() }
            ViewInfo(db, name, unquote(to.groupValues[1]), unquote(to.groupValues[2]), columns, row["s"].textValue(), create)
        }
    }

    /** Creates each view's `TO` table and the view itself on the target, if missing. */
    fun ensureOnTarget(
        sourceClient: ClickHouseClient,
        targetClient: ClickHouseClient,
        target: Target,
        views: List<ViewInfo>,
    ) {
        for (view in views) {
            val toInfo =
                SchemaInspector.read(sourceClient, view.toDatabase, view.toTable)
                    ?: throw SchemaException(
                        "view ${view.database}.${view.name} writes to ${view.toDatabase}.${view.toTable}, which is missing on DR",
                    )
            SchemaSync.ensure(targetClient, toInfo, target)
            if (targetClient.queryScalar("EXISTS TABLE ${Sql.ident(view.database)}.${Sql.ident(view.name)}") != "1") {
                targetClient.execute(
                    view.createStatement.replaceFirst("CREATE MATERIALIZED VIEW", "CREATE MATERIALIZED VIEW IF NOT EXISTS"),
                )
            }
        }
    }

    /** Rebuilds every view target of [database].[table] on [client] from the base table's current contents. */
    fun recompute(
        client: ClickHouseClient,
        database: String,
        table: String,
    ) {
        for (view in dependents(client, database, table)) {
            val to = "${Sql.ident(view.toDatabase)}.${Sql.ident(view.toTable)}"
            client.execute("TRUNCATE TABLE $to")
            client.execute("INSERT INTO $to (${view.columns.joinToString { Sql.ident(it) }}) ${view.asSelect}")
        }
    }

    private fun unquote(s: String) = s.removePrefix("`").removeSuffix("`")
}
