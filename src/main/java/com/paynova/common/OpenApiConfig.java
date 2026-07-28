package com.paynova.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares to Swagger UI that this API uses Bearer JWT authentication.
 * Without this declaration the Authorize button does not appear in the UI
 * (note: discovered during integration testing) — request enforcement lives in SecurityConfig,
 * but Swagger only reads this metadata declaration; the two are not synchronized automatically.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI payNovaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayNova Escrow API")
                        .version("0.1.0")
                        .description("Portfolio-grade sandbox escrow payment platform. "
                                + "Sandbox funds — no real monetary value. "
                                + "Money-write endpoints require an Idempotency-Key header (UUID)."))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
