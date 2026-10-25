package chrepl.control

import chrepl.config.model.Role
import java.time.Instant
import java.time.LocalDate

/** The requester recorded for scheduler-created jobs. */
const val SCHEDULER = "scheduler"

/** An authenticated caller: user name and OIDC groups (SPEC §7.4). */
data class Principal(
    val user: String,
    val groups: Set<String>,
) {
    companion object {
        val SYSTEM = Principal("system", emptySet())
    }
}

enum class JobState(
    val terminal: Boolean = false,
) {
    PENDING,
    PLANNING,
    RUNNING,
    FINALISING,
    SUCCEEDED(terminal = true),
    FAILED(terminal = true),
    BLOCKED(terminal = true),
    CANCELLING,
    CANCELLED(terminal = true),
}

enum class ChunkState { QUEUED, RUNNING, PREEMPTED, DONE, FAILED, CANCELLED }

enum class TaskState { WAITING, READY, ASSEMBLING, DONE, FAILED }

enum class CopyMode { REPLACE, SKIP_IF_PRESENT }

/** An ad-hoc copy request (SPEC §7.1, `POST /api/v1/jobs`). */
data class JobRequest(
    val dataset: String? = null,
    val tables: List<String> = emptyList(),
    val cobDates: List<LocalDate> = emptyList(),
    val target: String,
    val mode: CopyMode = CopyMode.REPLACE,
    val confirmOverwriteRecent: Boolean = false,
    val idempotencyKey: String? = null,
)

data class TableEstimate(
    val table: String,
    val partitions: Int,
    val rows: Long,
    val bytes: Long,
)

data class RecentCopy(
    val table: String,
    val cobDate: LocalDate?,
    val requester: String,
    val jobId: String,
    val copiedAt: Instant,
)

/** `POST /api/v1/estimates` (SPEC §7.1 step 4). */
data class Estimate(
    val tables: List<TableEstimate>,
    val bytes: Long,
    val sourceAvailable: Boolean,
    val sourceReason: String?,
    val queuedJobs: Long,
    /** Copies of this slice on the target in the last 7 days, by anyone. */
    val recentCopies: List<RecentCopy>,
    /** Submitting needs `confirm_overwrite_recent`: someone else copied the slice within the target's warning window. */
    val confirmationRequired: Boolean = false,
)

data class Progress(
    val chunksTotal: Int,
    val chunksDone: Int,
    val partitionsTotal: Int,
    val partitionsDone: Int,
    val rowsCopied: Long,
)

/** `GET /api/v1/jobs/{id}` (SPEC §7.2). */
data class JobView(
    val id: String,
    val kind: String,
    val requester: String,
    val requesters: List<String>,
    val target: String,
    val dataset: String?,
    val tables: List<String>,
    val cobDates: List<LocalDate>,
    val state: JobState,
    /** Present while the job or its chunks wait for the Source Guard (WAITING_FOR_SOURCE in the portal). */
    val waitReason: String?,
    val reason: String?,
    val queuePosition: Long?,
    val progress: Progress,
    val estimatedBytes: Long,
    val configVersion: String,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
) {
    val waitingForSource: Boolean get() = (state == JobState.PENDING || state == JobState.RUNNING) && waitReason != null
}

data class ChunkView(
    val id: Long,
    val table: String,
    val shard: String,
    val partitionId: String,
    val state: ChunkState,
    val attempts: Int,
    val preemptions: Int,
    val rows: Long?,
    val waitReason: String?,
    val error: String?,
)

/** Rejections that map to HTTP 4xx in the API. */
open class RequestException(
    message: String,
) : Exception(message)

class ForbiddenException(
    message: String,
) : RequestException(message)

class NotFoundException(
    message: String,
) : RequestException(message)

class QuotaException(
    message: String,
) : RequestException(message)

class ConflictException(
    message: String,
) : RequestException(message)

/** The roles a principal holds according to the config's access rules. */
data class Grants(
    val roles: Set<Role>,
) {
    /** Admins can do everything; operators and requesters are separate; everyone with a role can view. */
    fun has(role: Role): Boolean =
        when (role) {
            Role.VIEWER -> roles.isNotEmpty()
            Role.REQUESTER -> Role.REQUESTER in roles || Role.ADMIN in roles
            Role.OPERATOR -> Role.OPERATOR in roles || Role.ADMIN in roles
            Role.ADMIN -> Role.ADMIN in roles
        }
}
