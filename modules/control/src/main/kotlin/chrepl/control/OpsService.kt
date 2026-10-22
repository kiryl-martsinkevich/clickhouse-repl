package chrepl.control

import chrepl.config.model.Role
import chrepl.guard.GuardLevel
import chrepl.guard.Reason

/** `GET /api/v1/guard` (SPEC §7.5): levels and blocking reasons, never query text. */
data class GuardView(
    val level: GuardLevel?,
    val hosts: Map<String, GuardLevel>,
    val reasons: List<Reason>,
    val activeReads: Int,
    val paused: Boolean,
    val failover: Reason?,
    /** False when this process runs without a Source Guard (unguarded `dc: test` sources). */
    val guarded: Boolean,
)

/**
 * Operator and admin actions (SPEC §7.4, §7.5): the global pause, the DC failover flag and config
 * reload. Every action is checked against the caller's roles and written to the audit log.
 */
class OpsService(
    private val configs: ConfigRegistry,
    private val flags: PgOpsFlags,
    private val runtime: Runtime,
) {
    private fun require(
        principal: Principal,
        role: Role,
    ) {
        if (!AccessPolicy(configs.current).grants(principal).has(role)) {
            throw ForbiddenException("${principal.user} lacks the ${role.name.lowercase()} role")
        }
    }

    fun guard(principal: Principal): GuardView {
        require(principal, Role.VIEWER)
        val status = runtime.guard?.status()
        return GuardView(
            level = status?.level,
            hosts = status?.hosts ?: emptyMap(),
            reasons = status?.reasons ?: emptyList(),
            activeReads = status?.activeReads ?: 0,
            paused = flags.paused,
            failover = flags.failover,
            guarded = status != null,
        )
    }

    /** Global kill switch: running reads are pre-empted at the next guard poll and nothing new starts. */
    fun pause(
        principal: Principal,
        reason: String,
    ) {
        require(principal, Role.ADMIN)
        flags.set("paused", mapOf("reason" to reason), principal.user)
    }

    fun resume(principal: Principal) {
        require(principal, Role.ADMIN)
        flags.clear("paused", principal.user)
    }

    /** Failover runbook step 1 (SPEC §4.6): DR may become active, so no reads at all. */
    fun setFailover(
        principal: Principal,
        note: String,
    ) {
        require(principal, Role.OPERATOR)
        // Like the guard's own trigger, the first cause is kept so it stays visible.
        if (flags.failover != null) return
        flags.set("failover", Reason("DR_ACTIVE", null, "failover flag set by ${principal.user}: $note"), principal.user)
    }

    /** Clears the sticky DR_ACTIVE state once the DCs are back in their normal roles. */
    fun clearFailover(principal: Principal) {
        require(principal, Role.OPERATOR)
        flags.clear("failover", principal.user)
    }

    /** Validates the config file and activates it for new jobs; running jobs keep their version. */
    fun reloadConfig(principal: Principal): String {
        require(principal, Role.ADMIN)
        return configs.reload(principal.user)
    }
}
