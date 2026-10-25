package chrepl.control

import chrepl.config.model.ReplicatorConfig
import chrepl.config.validate.Code
import chrepl.config.validate.ConfigValidator
import chrepl.config.validate.HostResolver
import chrepl.config.validate.Issue
import chrepl.config.validate.ValidationOptions
import chrepl.guard.OpsFlags
import chrepl.guard.Reason
import org.slf4j.LoggerFactory

/**
 * Runtime re-check of the active-DC deny-list (SPEC §4.1, PLAN M3): DNS can change after start-up,
 * so every configured source and target host is resolved again periodically. A host that now
 * resolves into the active DC sets the sticky failover flag, which stops every read until an
 * operator clears it, and counts `chrepl_active_dc_violations_total` (must stay 0).
 */
class ActiveDcWatch(
    private val config: () -> ReplicatorConfig,
    private val flags: OpsFlags,
    private val resolver: HostResolver = HostResolver.SYSTEM,
) {
    private val codes = setOf(Code.ACTIVE_DC_HOST, Code.ACTIVE_DC_CIDR)

    fun check(): List<Issue> {
        val report = ConfigValidator(ValidationOptions(resolveDns = true, hostResolver = resolver)).validate(config())
        val violations = report.issues.filter { it.code in codes }
        for (issue in violations) {
            log.error("ACTIVE-DC VIOLATION {} at {}: {} — stopping all replication reads", issue.code, issue.path, issue.message)
            ControlMetrics.activeDcViolation(issue.code.name)
        }
        violations.firstOrNull()?.let { flags.setFailover(Reason("ACTIVE_DC", null, "${it.path}: ${it.message}")) }
        return violations
    }

    companion object {
        private val log = LoggerFactory.getLogger(ActiveDcWatch::class.java)
    }
}
