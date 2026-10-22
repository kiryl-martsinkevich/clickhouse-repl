package chrepl.app

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import io.swagger.v3.core.jackson.ModelResolver
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** OpenAPI document at `/v3/api-docs` (SPEC §7.5). */
@Configuration(proxyBeanMethods = false)
class OpenApi {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title(
                        "clickhouse-repl control plane",
                    ).version("v1")
                    .description("Ad-hoc copies, job status and operations (docs/SPEC.md §7.5)."),
            ).components(
                Components().addSecuritySchemes(
                    "oidc",
                    SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
                ),
            ).addSecurityItem(SecurityRequirement().addList("oidc"))

    /** The API serialises with snake_case names (`spring.jackson.property-naming-strategy`); the schema must match. */
    @Bean
    fun snakeCaseModels(): ModelResolver =
        ModelResolver(Json.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE))
}
