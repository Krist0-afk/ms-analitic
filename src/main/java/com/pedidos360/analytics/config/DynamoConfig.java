package com.pedidos360.analytics.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "app.dynamodb.habilitado", havingValue = "true")
public class DynamoConfig {

    // En la EC2 las credenciales vienen del rol de la instancia (LabInstanceProfile); no hay llaves en el código
    @Bean(destroyMethod = "close")
    public DynamoDbClient dynamoDbClient(@Value("${app.dynamodb.region}") String region,
                                         @Value("${app.dynamodb.endpoint:}") String endpoint) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            // DynamoDB Local acepta cualquier credencial
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }
}
