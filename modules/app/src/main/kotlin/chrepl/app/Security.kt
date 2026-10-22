package chrepl.app

import chrepl.control.Principal
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.web.SecurityFilterChain
import org.springframework.stereotype.Component

/**
 * OIDC resource server (SPEC §7.4, §11): every API call needs a bearer JWT from the configured
 * issuer (`spring.security.oauth2.resourceserver.jwt.*`). Without a JWT decoder configured the
 * application does not start. Authorisation is decided by the control plane from the token's
 * groups and the config's access rules, not here.
 *
 * The API is stateless and takes no cookies, so CSRF protection does not apply; the portal keeps
 * its token in memory (authorization code + PKCE, PLAN M6).
 */
@Configuration(proxyBeanMethods = false)
class Security {
    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                    ).permitAll()
                    .anyRequest()
                    .authenticated()
            }.oauth2ResourceServer { it.jwt(Customizer.withDefaults()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { it.disable() }
            .build()
}

/** Maps a validated JWT to the control plane's [Principal]. */
@Component
class Principals(
    private val props: AppProperties,
) {
    fun of(jwt: Jwt): Principal {
        val user = jwt.getClaimAsString(props.auth.userClaim) ?: jwt.subject ?: throw IllegalArgumentException("token has no user claim")
        val groups =
            when (val claim = jwt.claims[props.auth.groupsClaim]) {
                null -> emptySet()
                is Collection<*> -> claim.mapNotNull { it?.toString() }.toSet()
                // Some IdPs send a space- or comma-separated string.
                else ->
                    claim
                        .toString()
                        .split(' ', ',')
                        .filter { it.isNotBlank() }
                        .toSet()
            }
        return Principal(user, groups)
    }
}
