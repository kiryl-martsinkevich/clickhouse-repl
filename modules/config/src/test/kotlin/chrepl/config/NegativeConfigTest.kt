package chrepl.config

import chrepl.config.validate.Code
import chrepl.config.validate.Severity
import chrepl.config.validate.ValidationOptions
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Each case applies one mutation to the example config and expects one specific issue.
 * Covers the PLAN M0 exit criteria and the remaining SPEC §9.3 rules.
 */
class NegativeConfigTest {
    class Case(
        private val name: String,
        val code: Code,
        val path: String,
        val severity: Severity = Severity.ERROR,
        val options: ValidationOptions = ValidationOptions(),
        val mutate: ObjectNode.() -> Unit,
    ) {
        override fun toString() = name
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `mutation is reported`(case: Case) {
        val tree = Fixtures.exampleTree()
        case.mutate(tree)
        Fixtures.check(tree, case.options).assertHas(case.code, case.path, case.severity)
    }

    companion object {
        private val dnsWithActiveDcTarget =
            ValidationOptions(
                resolveDns = true,
                hostResolver = Fixtures.resolver(mapOf("ch-qa1-s1.nonprod.internal" to "10.10.4.20")),
            )

        @JvmStatic
        fun cases() =
            listOf(
                // --- PLAN M0 exit criteria
                Case("table without column policies", Code.UNCLASSIFIED_COLUMNS, "tables[0].columns") {
                    obj("/tables/0").remove("columns")
                },
                Case("unlisted_columns pass without non_pii approval", Code.UNCLASSIFIED_COLUMNS, "tables[0].unlisted_columns") {
                    obj("/tables/0").put("unlisted_columns", "pass")
                },
                Case("masked column in partition key", Code.PARTITION_KEY_MASKED, "tables[0].columns.customer_id") {
                    obj("/tables/0").put("partition_key", "(toYYYYMM(trade_date), customer_id)")
                },
                Case("source host in active_dc.hosts", Code.ACTIVE_DC_HOST, "sources.dr.shards[0].host") {
                    obj("/sources/dr/shards/0").put("host", "ch-prod-s1.dc-prod.internal:8443")
                },
                Case(
                    "target host resolving into active_dc.cidrs",
                    Code.ACTIVE_DC_CIDR,
                    "targets.qa1.hosts[0]",
                    options = dnsWithActiveDcTarget,
                ) {},
                Case("source not in the DR DC", Code.SOURCE_DC, "sources.dr.dc") {
                    obj("/sources/dr").put("dc", "prod")
                },
                Case("literal masking key", Code.SECRET_LITERAL, "masking.key.ref") {
                    obj("/masking/key").put("ref", "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
                },
                Case("unknown masking domain", Code.UNKNOWN_DOMAIN, "tables[0].columns.customer_id") {
                    obj("/tables/0/columns/customer_id").put("mask", "cust_id")
                },
                Case("pre-emption disabled", Code.PREEMPTION_DISABLED, "guard.preemption.enabled") {
                    obj("/guard/preemption").put("enabled", false)
                },
                // --- isolation
                Case("active DC with no hosts or CIDRs", Code.ACTIVE_DC_EMPTY, "active_dc") {
                    obj("/active_dc").apply {
                        putArray("hosts")
                        putArray("cidrs")
                    }
                },
                Case("invalid CIDR", Code.INVALID_CIDR, "active_dc.cidrs[0]") {
                    arr("/active_dc/cidrs").set(0, textNode("10.10.0.0/40"))
                },
                Case("IP literal target inside active-DC CIDR", Code.ACTIVE_DC_CIDR, "targets.uat.hosts[0]") {
                    arr("/targets/uat/hosts").set(0, textNode("10.10.9.9:8443"))
                },
                Case("unresolvable host with DNS checks", Code.DNS_UNRESOLVED, "sources.dr.shards[1].host", options = unresolvable()) {},
                Case("target pointing at a DR node", Code.TARGET_IS_SOURCE, "targets.uat.hosts[0]") {
                    arr("/targets/uat/hosts").set(0, textNode("ch-dr-s2.dc-dr.internal:8443"))
                },
                Case("duplicate source host", Code.DUPLICATE_HOST, "sources.dr.shards[1].host") {
                    obj("/sources/dr/shards/1").put("host", "ch-dr-s1.dc-dr.internal:8443")
                },
                // --- sources and targets
                Case("foreign-activity detection disabled", Code.DC_ROLE_CHECK_DISABLED, "sources.dr.dc_role_check.foreign_activity") {
                    obj("/sources/dr/dc_role_check").put("foreign_activity", false)
                },
                Case("reader and guard share a user", Code.SOURCE_USERS, "sources.dr.guard.user") {
                    obj("/sources/dr/guard").put("user", "repl_reader")
                },
                Case("literal password", Code.SECRET_LITERAL, "targets.qa1.writer.password") {
                    obj("/targets/qa1/writer").put("password", "hunter2")
                },
                Case("unsupported secret scheme", Code.SECRET_SCHEME, "sources.dr.reader.password") {
                    obj("/sources/dr/reader").put("password", "\${vault:secret/chrepl#pw}")
                },
                Case("environment marker differs from target name", Code.TARGET_MARKER, "targets.qa1.environment_marker") {
                    obj("/targets/qa1").put("environment_marker", "qa2")
                },
                Case("target named like production", Code.TARGET_MARKER, "targets.prod.environment_marker") {
                    val qa1 = obj("/targets").remove("qa1") as ObjectNode
                    qa1.put("environment_marker", "prod")
                    obj("/targets").set<ObjectNode>("prod", qa1)
                },
                Case("same_shards with a different shard count", Code.SHARD_MAPPING, "targets.qa1.shard_mapping") {
                    obj("/targets/qa1").put("shards", 2)
                },
                // --- masking
                Case("Fortanix provider before M8", Code.KEY_PROVIDER, "masking.key.provider") {
                    obj("/masking/key").put("provider", "fortanix")
                },
                Case("fpe_ff1 without approval", Code.STRATEGY_OPTION_MISSING, "masking.domains.customer_id.approved_by") {
                    obj("/masking/domains/customer_id").remove("approved_by")
                },
                Case("option that does not apply to the strategy", Code.STRATEGY_OPTION_UNSUPPORTED, "masking.domains.phone.max_days") {
                    obj("/masking/domains/phone").put("max_days", 5)
                },
                Case("numeric noise out of range", Code.STRATEGY_OPTION_INVALID, "masking.domains.amount_noise.relative") {
                    obj("/masking/domains/amount_noise").put("relative", 1.5)
                },
                Case("unknown dictionary", Code.UNKNOWN_DICTIONARY, "masking.domains.first_name.dictionary") {
                    obj("/masking/domains/first_name").put("dictionary", "nicknames")
                },
                Case(
                    "missing dictionary file",
                    Code.DICTIONARY_FILE,
                    "masking.dictionaries.first_names.file",
                    options = ValidationOptions(checkFiles = true),
                ) {
                },
                // --- tables
                Case("table name without database", Code.TABLE_NAME, "tables[0].source") {
                    obj("/tables/0").put("source", "trades")
                },
                Case("duplicate table", Code.DUPLICATE_TABLE, "tables") {
                    obj("/tables/1").put("source", "trading.trades")
                },
                Case("cob_column and full_copy together", Code.COB_OR_FULL_COPY, "tables[0]") {
                    obj("/tables/0").put("full_copy", true)
                },
                Case("full copy without a size cap", Code.FULL_COPY_CAP, "tables[2].max_full_copy_bytes") {
                    obj("/tables/2").remove("max_full_copy_bytes")
                },
                Case("non_pii without approver", Code.APPROVAL_REQUIRED, "tables[3].approved_by") {
                    obj("/tables/3").remove("approved_by")
                },
                Case("masked COB column", Code.COB_MASKED, "tables[1].columns.cob_date") {
                    obj("/tables/1/columns").putObject("cob_date").put("mask", "free_text")
                },
                Case("unlisted COB column", Code.COB_COLUMN, "tables[1].cob_column") {
                    obj("/tables/1/columns").remove("cob_date")
                },
                Case("unknown entity column", Code.ENTITY_COLUMN, "tables[0].columns.notional.entity_column") {
                    obj("/tables/0/columns/notional").put("entity_column", "client")
                },
                Case("date_shift without entity column", Code.ENTITY_COLUMN_REQUIRED, "tables[2].columns.date_of_birth") {
                    obj("/tables/2/columns/date_of_birth").remove("entity_column")
                },
                Case("invalid JSON path", Code.JSON_PATH, "tables[2].columns.profile_json.json_paths") {
                    obj("/tables/2/columns/profile_json/json_paths").putObject("contact.fax").put("mask", "phone")
                },
                Case("masked sharding key with same_shards target", Code.SHARDING_KEY_MASKED, "tables[0].sharding_key", Severity.WARNING) {
                    obj("/tables/0").put("sharding_key", "cityHash64(customer_id)")
                },
                Case("COB column outside the partition key", Code.COB_NOT_IN_PARTITION_KEY, "tables[0].partition_key", Severity.WARNING) {
                    obj("/tables/0").put("partition_key", "toYYYYMM(inserted_at)")
                },
                Case("undeclared partition key", Code.PARTITION_KEY_UNDECLARED, "tables[0].partition_key", Severity.WARNING) {
                    obj("/tables/0").remove("partition_key")
                },
                Case("chunk above the L1 read limit", Code.CHUNK_LIMITS, "tables[0].chunk.max_bytes") {
                    obj("/tables/0/chunk").put("max_bytes", "100GiB")
                },
                // --- datasets and continuous jobs
                Case("dataset with unknown table", Code.UNKNOWN_TABLE, "datasets[1].tables[0]") {
                    arr("/datasets/1/tables").set(0, textNode("trading.orders"))
                },
                Case("continuous interval below stable window", Code.INTERVAL_TOO_SHORT, "continuous[0].interval") {
                    obj("/continuous/0").put("interval", "30s")
                },
                Case("continuous job with unknown target", Code.UNKNOWN_TARGET, "continuous[1].targets[0]") {
                    arr("/continuous/1/targets").set(0, textNode("perf"))
                },
                Case("watermark mode without watermark column", Code.CONTINUOUS_OPTION_MISSING, "continuous[0].watermark_column") {
                    obj("/continuous/0").remove("watermark_column")
                },
                Case("masked watermark column", Code.WATERMARK_COLUMN, "continuous[0].watermark_column") {
                    obj("/continuous/0").put("watermark_column", "customer_id")
                },
                Case("option that does not apply to the mode", Code.CONTINUOUS_OPTION_UNSUPPORTED, "continuous[1].overlap") {
                    obj("/continuous/1").put("overlap", "5m")
                },
                Case("invalid cron", Code.INVALID_CRON, "continuous[2].cron") {
                    obj("/continuous/2").put("cron", "0 25 * * *")
                },
                Case("invalid timezone", Code.INVALID_TIMEZONE, "guard.blackout_windows[0].timezone") {
                    obj("/guard/blackout_windows/0").put("timezone", "Europe/Atlantis")
                },
                // --- guard
                Case("guard ignores readonly replicas", Code.UNSAFE_GUARD_SETTING, "guard.thresholds.replication.block_on_readonly") {
                    obj("/guard/thresholds/replication").put("block_on_readonly", false)
                },
                Case("per-host concurrency above global", Code.GUARD_CONCURRENCY, "guard.concurrency") {
                    obj("/guard/concurrency").put("per_host", 3)
                },
                Case("stale_after shorter than two polls", Code.GUARD_TIMING, "guard.stale_after") {
                    obj("/guard").put("stale_after", "6s")
                },
                Case("ratio threshold above 1", Code.GUARD_THRESHOLD, "guard.thresholds.host.max_cpu_utilisation") {
                    obj("/guard/thresholds/host").put("max_cpu_utilisation", 1.5)
                },
                // --- access and scheduling
                Case("access rule with unknown target", Code.UNKNOWN_TARGET, "access[0].targets[0]") {
                    arr("/access/0/targets").set(0, textNode("perf"))
                },
                Case("requester without quota", Code.QUOTA_REQUIRED, "access[0].quota") {
                    obj("/access/0").remove("quota")
                },
                Case("duplicate access group", Code.DUPLICATE_GROUP, "access") {
                    obj("/access/1").put("group", "qa-trading")
                },
                // --- binding
                Case("unknown field", Code.UNKNOWN_FIELD, "guard.bogus") {
                    obj("/guard").put("bogus", 1)
                },
                Case("malformed duration", Code.INVALID_VALUE, "guard.poll_interval") {
                    obj("/guard").put("poll_interval", "5 seconds")
                },
                Case("missing required field", Code.MISSING_FIELD, "guard.stable_window") {
                    obj("/guard").remove("stable_window")
                },
                Case("unknown enum value", Code.INVALID_VALUE, "targets.qa1.shard_mapping") {
                    obj("/targets/qa1").put("shard_mapping", "round_robin")
                },
                Case("unknown column policy", Code.INVALID_VALUE, "tables[0].columns.trade_id") {
                    obj("/tables/0/columns").put("trade_id", "drop")
                },
                Case("unsupported version", Code.UNSUPPORTED_VERSION, "version") {
                    put("version", 2)
                },
            )

        private fun unresolvable() =
            ValidationOptions(
                resolveDns = true,
                hostResolver = { host -> if (host == "ch-dr-s2.dc-dr.internal") emptyList() else Fixtures.resolver().resolve(host) },
            )
    }
}
