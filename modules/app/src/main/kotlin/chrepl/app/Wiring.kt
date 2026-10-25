package chrepl.app

import chrepl.config.validate.SecretReader
import chrepl.config.validate.ValidationOptions
import chrepl.control.ConfigRegistry
import chrepl.control.ContinuousService
import chrepl.control.Db
import chrepl.control.JobService
import chrepl.control.OpsService
import chrepl.control.PgOpsFlags
import chrepl.control.Runtime
import chrepl.control.Scheduler
import chrepl.control.Worker
import chrepl.control.WorkerSettings
import chrepl.guard.GuardFactory
import chrepl.transfer.ClientFactory
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.InetAddress
import java.util.UUID
import javax.sql.DataSource

/** Wires the control plane (modules/control) into Spring. */
@Configuration(proxyBeanMethods = false)
class Wiring(
    private val props: AppProperties,
) {
    private val hostName = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("chrepl")
    private val processId = "$hostName-${UUID.randomUUID().toString().take(6)}"

    @Bean
    fun secretReader(): SecretReader = SecretReader.SYSTEM

    @Bean
    fun db(dataSource: DataSource): Db = Db(dataSource).also { it.migrate() }

    /** Loads and validates the config at startup; an invalid config stops the process (fail closed). */
    @Bean
    fun configRegistry(
        db: Db,
        secrets: SecretReader,
    ): ConfigRegistry {
        val v = props.validation
        val options =
            ValidationOptions(
                resolveDns = v.resolveDns,
                resolveSecrets = v.resolveSecrets,
                checkFiles = v.checkFiles,
                secretReader = secrets,
            )
        return ConfigRegistry(db, props.config, options).also { it.reload("startup:$processId") }
    }

    @Bean
    fun opsFlags(db: Db): PgOpsFlags = PgOpsFlags(db)

    /**
     * Every process that reads from DR (workers) or queries DR metadata (API estimates) runs its own
     * Source Guard; cluster-wide read limits are enforced through Postgres read tokens.
     * Sources and guard settings are read at startup, so changing them needs a restart.
     */
    @Bean(destroyMethod = "close")
    fun runtime(
        configs: ConfigRegistry,
        db: Db,
        secrets: SecretReader,
        flags: PgOpsFlags,
    ): Runtime {
        val config = configs.current
        val source = config.sources.values.single()
        val guard = if (source.dc == "test") null else GuardFactory.create(config.guard, source, ClientFactory(secrets), flags).start()
        if (guard == null) log.warn("source {} has dc: test; reads are NOT guarded (dev/test only)", config.sources.keys.single())
        return Runtime(configs, db, secrets, guard, processId, props.guardWait)
    }

    @Bean
    fun jobService(
        db: Db,
        configs: ConfigRegistry,
        runtime: Runtime,
    ) = JobService(db, configs, runtime)

    @Bean
    fun opsService(
        configs: ConfigRegistry,
        flags: PgOpsFlags,
        runtime: Runtime,
    ) = OpsService(configs, flags, runtime)

    @Bean
    fun continuousService(
        db: Db,
        configs: ConfigRegistry,
    ) = ContinuousService(db, configs)

    @Bean
    fun schedulerLifecycle(
        db: Db,
        configs: ConfigRegistry,
        runtime: Runtime,
        jobs: JobService,
    ) = SchedulerLifecycle(props, db, configs, runtime, jobs)

    @Bean
    fun workerLifecycle(
        db: Db,
        configs: ConfigRegistry,
        runtime: Runtime,
    ) = WorkerLifecycle(props, db, configs, runtime, processId)

    companion object {
        private val log = LoggerFactory.getLogger(Wiring::class.java)
    }
}

/** Starts the job worker in processes with the `worker` role and stops it before the runtime closes. */
class WorkerLifecycle(
    private val props: AppProperties,
    private val db: Db,
    private val configs: ConfigRegistry,
    private val runtime: Runtime,
    private val processId: String,
) : SmartLifecycle {
    @Volatile private var worker: Worker? = null

    override fun start() {
        if (!props.has(ProcessRole.WORKER)) return
        val w = props.worker
        worker =
            Worker(db, configs, runtime, processId, WorkerSettings(threads = w.threads, lease = w.lease, idleSleep = w.idleSleep)).start()
    }

    override fun stop() {
        worker?.close()
        worker = null
    }

    override fun isRunning() = worker != null
}

/** Runs the continuous-mode scheduler in processes with the `scheduler` role; only the lock holder schedules. */
class SchedulerLifecycle(
    private val props: AppProperties,
    private val db: Db,
    private val configs: ConfigRegistry,
    private val runtime: Runtime,
    private val jobs: JobService,
) : SmartLifecycle {
    @Volatile private var scheduler: Scheduler? = null

    override fun start() {
        if (!props.has(ProcessRole.SCHEDULER)) return
        scheduler = Scheduler(db, configs, runtime, jobs).start()
    }

    override fun stop() {
        scheduler?.close()
        scheduler = null
    }

    override fun isRunning() = scheduler != null
}
