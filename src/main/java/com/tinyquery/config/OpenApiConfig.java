package com.tinyquery.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tinyQueryOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("LynMill API")
                        .description("轻量级本地查询引擎")
                        .version("1.1.0"));
    }
}
