package com.influora.config;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@ConditionalOnProperty(prefix = "influora.r2", name = "account-id")
public class R2Config {

    @Bean
    S3Client r2S3Client(R2Properties props) {
        return S3Client.builder()
                .region(Region.of("auto"))
                .endpointOverride(URI.create(props.resolvedEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKeyId(), props.getSecretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    S3Presigner r2S3Presigner(R2Properties props) {
        return S3Presigner.builder()
                .region(Region.of("auto"))
                .endpointOverride(URI.create(props.resolvedEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKeyId(), props.getSecretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
