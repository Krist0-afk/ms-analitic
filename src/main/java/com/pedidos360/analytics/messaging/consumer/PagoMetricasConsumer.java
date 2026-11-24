package com.pedidos360.analytics.messaging.consumer;

import com.pedidos360.analytics.dto.PagoEvent;
import com.pedidos360.analytics.service.MetricasService;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

// Grupo analytics-pagos: pagos aprobados, rechazados y alertas de fraude en vivo
@Component
public class PagoMetricasConsumer implements ConsumerSeekAware {

    private final MetricasService metricas;

    public PagoMetricasConsumer(MetricasService metricas) {
        this.metricas = metricas;
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> asignadas, ConsumerSeekCallback callback) {
        callback.seekToBeginning(asignadas.keySet());
    }

    @KafkaListener(topics = "${app.kafka.topicos.pagos}", groupId = "${app.kafka.grupos.pagos}",
            properties = "spring.json.value.default.type=com.pedidos360.analytics.dto.PagoEvent")
    public void consumir(@Payload PagoEvent pago) {
        metricas.registrarPago(pago);
    }
}
