package com.influora;

import com.influora.config.CompanyTaxProperties;
import com.influora.config.InternalServiceTokenProperties;
import com.influora.config.JwtProperties;
import com.influora.config.MeeraStreamProperties;
import com.influora.config.MetaApiProperties;
import com.influora.config.R2Properties;
import com.influora.config.RazorpayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({
    R2Properties.class,
    JwtProperties.class,
    MeeraStreamProperties.class,
    RazorpayProperties.class,
    InternalServiceTokenProperties.class,
    MetaApiProperties.class,
    CompanyTaxProperties.class
})
// Required for @Scheduled to actually fire (EmailWorker, and Phase 2's MetricsPollingJob) — was
// missing before Phase 2; without it every @Scheduled method in the app is silently inert.
@EnableScheduling
public class InfluoraApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfluoraApiApplication.class, args);
    }
}
