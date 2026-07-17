package com.influora;

import com.influora.config.InternalServiceTokenProperties;
import com.influora.config.JwtProperties;
import com.influora.config.MeeraStreamProperties;
import com.influora.config.R2Properties;
import com.influora.config.RazorpayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    R2Properties.class,
    JwtProperties.class,
    MeeraStreamProperties.class,
    RazorpayProperties.class,
    InternalServiceTokenProperties.class
})
public class InfluoraApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfluoraApiApplication.class, args);
    }
}
