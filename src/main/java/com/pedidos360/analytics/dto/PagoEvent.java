package com.pedidos360.analytics.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

// Evento del tópico pedidos360.pagos (lo publica ms-pago)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PagoEvent(
        String eventoId,
        Long pagoId,
        Long ordenId,
        String usuarioEmail,
        Double monto,
        String ultimos4,
        String estado,
        String motivo,
        Instant ocurridoEn) {

    public void validar() {
        if (eventoId == null || estado == null || ocurridoEn == null) {
            throw new IllegalArgumentException("Evento de pago incompleto: " + this);
        }
    }
}
