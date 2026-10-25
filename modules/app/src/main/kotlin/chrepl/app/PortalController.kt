package chrepl.app

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class PortalConfig(
    val issuer: String,
    val clientId: String,
    val scope: String,
)

/** Public settings the portal needs before sign-in (no secrets: the portal is a public PKCE client). */
@RestController
class PortalController(
    private val props: AppProperties,
) {
    @GetMapping("/portal/config.json")
    fun config() = PortalConfig(props.portal.issuer, props.portal.clientId, props.portal.scope)
}
