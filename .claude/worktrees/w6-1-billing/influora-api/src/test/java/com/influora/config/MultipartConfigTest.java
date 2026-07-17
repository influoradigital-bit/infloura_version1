package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.util.unit.DataSize;

/** Kabir H-19-1 — servlet multipart limits match deliverable upload spec. */
@SpringJUnitConfig
@EnableConfigurationProperties(MultipartProperties.class)
@ContextConfiguration(initializers = ConfigDataApplicationContextInitializer.class)
class MultipartConfigTest {

    @Autowired private MultipartProperties multipartProperties;

    @Test
    @DisplayName("application.yml: multipart limits are 500MB per file and 1GB per request")
    void multipartLimitsMatchDeliverableSpec() {
        assertEquals(DataSize.ofMegabytes(500), multipartProperties.getMaxFileSize());
        assertEquals(DataSize.ofGigabytes(1), multipartProperties.getMaxRequestSize());
    }
}
