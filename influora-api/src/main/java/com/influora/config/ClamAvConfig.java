package com.influora.config;

import com.influora.integration.clamav.ClamAvClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** S7 — same pattern as {@link R2Config}: build the integration client from its properties. */
@Configuration
public class ClamAvConfig {

    @Bean
    ClamAvClient clamAvClient(ClamAvProperties props) {
        return new ClamAvClient(
                props.getHost(), props.getPort(), props.getConnectTimeoutMs(), props.getReadTimeoutMs());
    }
}
