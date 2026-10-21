package com.veriqra.app;

import com.veriqra.app.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the VeriQra attendance platform.
 *
 * <p>The application is a Spring Boot service that exposes a JSON API and serves the
 * installable PWA client from {@code src/main/resources/static}. All trust decisions
 * (QR authenticity, face match, liveness, attendance eligibility) are made server side.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
public class VeriQraApplication {

    public static void main(String[] args) {
        SpringApplication.run(VeriQraApplication.class, args);
    }
}
