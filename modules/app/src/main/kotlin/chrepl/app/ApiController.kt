package chrepl.app

import chrepl.config.model.Role
import chrepl.control.Catalog
import chrepl.control.ChunkView
import chrepl.control.CopyMode
import chrepl.control.Estimate
import chrepl.control.GuardView
import chrepl.control.JobRequest
import chrepl.control.JobService
import chrepl.control.JobState
import chrepl.control.JobView
import chrepl.control.OpsService
import chrepl.control.RecentCopy
import chrepl.control.RequestException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** COB selection: explicit dates, or an inclusive calendar range (SPEC §7.1 step 2). */
data class CobSpec(
    val dates: List<LocalDate> = emptyList(),
    val from: LocalDate? = null,
    val to: LocalDate? = null,
) {
    fun expand(): List<LocalDate> {
        if (from == null && to == null) return dates.distinct().sorted()
        if (dates.isNotEmpty() || from == null || to == null) throw RequestException("cob takes either dates or both from and to")
        if (to < from) throw RequestException("cob.to is before cob.from")
        if (from.plusDays(MAX_RANGE_DAYS) <= to) throw RequestException("a COB range covers at most $MAX_RANGE_DAYS days")
        return generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()
    }

    companion object {
        const val MAX_RANGE_DAYS = 366L
    }
}

/** `POST /api/v1/jobs` and `POST /api/v1/estimates` body (SPEC §7.5). */
data class SubmitBody(
    val dataset: String? = null,
    val tables: List<String> = emptyList(),
    val cob: CobSpec? = null,
    val target: String,
    /** `replace` (default) or `skip_if_present`. */
    val mode: String = "replace",
    val confirmOverwriteRecent: Boolean = false,
) {
    fun toRequest(idempotencyKey: String?): JobRequest {
        val copyMode =
            CopyMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
                ?: throw RequestException("mode must be replace or skip_if_present")
        return JobRequest(dataset, tables, cob?.expand() ?: emptyList(), target, copyMode, confirmOverwriteRecent, idempotencyKey)
    }
}

data class Me(
    val user: String,
    val groups: Set<String>,
    val roles: List<String>,
)

data class NoteBody(
    val note: String = "",
)

data class ReloadResult(
    val version: String,
)

/** Registers the API only in processes with the `api` role. */
class ApiRoleCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean {
        val roles =
            Binder.get(context.environment).bind("chrepl.roles", Array<ProcessRole>::class.java).orElse(null) ?: arrayOf(ProcessRole.ALL)
        return ProcessRole.ALL in roles || ProcessRole.API in roles
    }
}

/** REST API v1 (SPEC §7.5). Authorisation happens in the services, from the caller's groups. */
@RestController
@RequestMapping("/api/v1")
@Conditional(ApiRoleCondition::class)
class ApiController(
    private val jobs: JobService,
    private val ops: OpsService,
    private val principals: Principals,
) {
    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal jwt: Jwt,
    ): Me {
        val principal = principals.of(jwt)
        val grants = jobs.grants(principal)
        return Me(principal.user, principal.groups, Role.entries.filter { grants.has(it) }.map { it.name.lowercase() })
    }

    @GetMapping("/catalog")
    fun catalog(
        @AuthenticationPrincipal jwt: Jwt,
    ): Catalog = jobs.catalog(principals.of(jwt))

    @PostMapping("/estimates")
    fun estimate(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody body: SubmitBody,
    ): Estimate = jobs.estimate(principals.of(jwt), body.toRequest(null))

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun submit(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody body: SubmitBody,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): JobView = jobs.submit(principals.of(jwt), body.toRequest(idempotencyKey?.take(200)))

    @GetMapping("/jobs")
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "false") mine: Boolean,
        @RequestParam(required = false) state: JobState?,
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<JobView> = jobs.list(principals.of(jwt), mine, state, limit.coerceIn(1, 500))

    @GetMapping("/jobs/{id}")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: String,
    ): JobView = jobs.get(principals.of(jwt), id)

    @GetMapping("/jobs/{id}/chunks")
    fun chunks(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: String,
    ): List<ChunkView> = jobs.chunks(principals.of(jwt), id)

    @PostMapping("/jobs/{id}/cancel")
    fun cancel(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: String,
    ): JobView = jobs.cancel(principals.of(jwt), id)

    @GetMapping("/targets/{target}/history")
    fun history(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable target: String,
        @RequestParam(required = false) table: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) cob: LocalDate?,
    ): List<RecentCopy> = jobs.history(principals.of(jwt), target, table, cob)

    @GetMapping("/guard")
    fun guard(
        @AuthenticationPrincipal jwt: Jwt,
    ): GuardView = ops.guard(principals.of(jwt))

    @PostMapping("/admin/failover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun failover(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody(required = false) body: NoteBody?,
    ) = ops.setFailover(principals.of(jwt), body?.note ?: "")

    @PostMapping("/admin/failover/clear")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun clearFailover(
        @AuthenticationPrincipal jwt: Jwt,
    ) = ops.clearFailover(principals.of(jwt))

    @PostMapping("/admin/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun pause(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody(required = false) body: NoteBody?,
    ) = ops.pause(principals.of(jwt), body?.note ?: "")

    @PostMapping("/admin/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resume(
        @AuthenticationPrincipal jwt: Jwt,
    ) = ops.resume(principals.of(jwt))

    @PostMapping("/admin/config/reload")
    fun reload(
        @AuthenticationPrincipal jwt: Jwt,
    ): ReloadResult = ReloadResult(ops.reloadConfig(principals.of(jwt)))
}
