package com.pedidos360.analytics.messaging.consumer;

import com.pedidos360.analytics.dto.InteraccionEvent;
import com.pedidos360.analytics.service.RecomendacionService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

// Grupo analytics-recomendaciones: actualiza el perfil de navegación de cada usuario
@Component
public class InteraccionRecomendacionesConsumer {

    private final RecomendacionService recomendaciones;

    public InteraccionRecomendacionesConsumer(RecomendacionService recomendaciones) {
        this.recomendaciones = recomendaciones;
    }

    @KafkaListener(topics = "${app.kafka.topicos.interacciones}", groupId = "${app.kafka.grupos.recomendaciones}",
            properties = "spring.json.value.default.type=com.pedidos360.analytics.dto.InteraccionEvent")
    public void consumir(@Payload InteraccionEvent evento) {
        recomendaciones.procesar(evento);
    }
}
