package com.pedidos360.analytics.messaging.consumer;

import com.pedidos360.analytics.dto.InteraccionEvent;
import com.pedidos360.analytics.service.MetricasService;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

// Grupo analytics-metricas: cuenta clics y productos más vistos para el dashboard.
// Las métricas viven en memoria, así que al arrancar se relee el tópico desde el inicio
// (lo que la retención de 7 días conserva) para reconstruirlas.
@Component
public class InteraccionMetricasConsumer implements ConsumerSeekAware {

    private final MetricasService metricas;

    public InteraccionMetricasConsumer(MetricasService metricas) {
        this.metricas = metricas;
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> asignadas, ConsumerSeekCallback callback) {
        callback.seekToBeginning(asignadas.keySet());
    }

    @KafkaListener(topics = "${app.kafka.topicos.interacciones}", groupId = "${app.kafka.grupos.metricas}",
            properties = "spring.json.value.default.type=com.pedidos360.analytics.dto.InteraccionEvent")
    public void consumir(@Payload InteraccionEvent evento) {
        metricas.registrarInteraccion(evento);
    }
}
