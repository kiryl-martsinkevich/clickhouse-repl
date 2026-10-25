package chrepl.app

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

/** `api` serves the REST API, `worker` executes jobs, `scheduler` runs continuous mode (one leader at a time). */
enum class ProcessRole { API, WORKER, SCHEDULER, ALL }

/** `chrepl.*` application properties. The replication config itself is the YAML at [config]. */
@ConfigurationProperties("chrepl")
data class AppProperties(
    /** The replicator config file or directory (SPEC §9). */
    val config: Path,
    val roles: Set<ProcessRole> = setOf(ProcessRole.ALL),
    val worker: WorkerProperties = WorkerProperties(),
    val auth: AuthProperties = AuthProperties(),
    val portal: PortalProperties = PortalProperties(),
    /** How long a chunk may wait for the Source Guard before it is re-queued. */
    val guardWait: Duration = Duration.ofHours(1),
    /** Online config checks at load time (SPEC §9.3); all on in production. */
    val validation: ValidationProperties = ValidationProperties(),
) {
    fun has(role: ProcessRole) = ProcessRole.ALL in roles || role in roles
}

data class WorkerProperties(
    val threads: Int = 4,
    val lease: Duration = Duration.ofSeconds(60),
    val idleSleep: Duration = Duration.ofMillis(500),
)

/** Which JWT claims carry the user name and the OIDC groups (SPEC §7.4). */
data class AuthProperties(
    val userClaim: String = "preferred_username",
    val groupsClaim: String = "groups",
)

/** The portal's OIDC client (authorization code + PKCE in the browser, SPEC §7.1). */
data class PortalProperties(
    /** The issuer the browser signs in with; empty disables the portal. */
    val issuer: String = "",
    val clientId: String = "chrepl-portal",
    val scope: String = "openid profile",
)

data class ValidationProperties(
    val resolveDns: Boolean = true,
    val resolveSecrets: Boolean = true,
    val checkFiles: Boolean = true,
)
