package com.pedidos360.analytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    // Reloj inyectable: permite probar las métricas por minuto con una hora fija
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
