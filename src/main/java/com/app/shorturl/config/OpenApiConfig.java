package com.app.shorturl.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI shorturlOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Nusantara Link API")
                        .version("1.0.0")
                        .description("REST API untuk Short URL Service dengan tema kebudayaan Indonesia.\n\n" +
                                "**Endpoint publik:**\n" +
                                "- POST `/api/v1/shorten` — buat short URL (no auth)\n" +
                                "- GET `/api/v1/info/{code}` — info short URL\n\n" +
                                "**Endpoint admin** (butuh Basic Auth):\n" +
                                "- GET `/api/v1/admin/urls` — list semua URL\n" +
                                "- DELETE `/api/v1/admin/urls/{id}` — hapus URL\n" +
                                "- PATCH `/api/v1/admin/urls/{id}/toggle` — toggle aktif/nonaktif")
                        .contact(new Contact()
                                .name("Admin Nusantara Link")
                                .email("admin@nusantara.link"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .components(new Components()
                        .addSecuritySchemes("basicAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("basic")
                                        .description("Basic authentication untuk endpoint admin. " +
                                                "Default: admin / admin123")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
    }
}
