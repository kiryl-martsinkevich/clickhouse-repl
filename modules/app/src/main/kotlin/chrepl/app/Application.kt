package chrepl.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * The control plane (SPEC §3): one artefact started with different process roles. `api` serves the
 * REST API, `worker` executes jobs; `all` runs both in one process.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
