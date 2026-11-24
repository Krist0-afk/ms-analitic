package com.pedidos360.analytics.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

// Evento del tópico pedidos360.interacciones (lo publican catálogo y compras)
@JsonIgnoreProperties(ignoreUnknown = true)
public record InteraccionEvent(
        String eventoId,
        String tipo,
        String usuarioEmail,
        Long productoId,
        String nombreProducto,
        String categoria,
        Integer cantidad,
        Double valor,
        String origen,
        Instant ocurridoEn) {

    public static final String PRODUCTO_VISTO = "PRODUCTO_VISTO";
    public static final String CARRITO_AGREGADO = "CARRITO_AGREGADO";
    public static final String CARRITO_QUITADO = "CARRITO_QUITADO";

    // Un evento sin id, tipo o usuario no se puede procesar: va al DLT
    public void validar() {
        if (eventoId == null || tipo == null || usuarioEmail == null || ocurridoEn == null) {
            throw new IllegalArgumentException("Evento de interacción incompleto: " + this);
        }
    }

    public boolean anonimo() {
        return "anonimo".equals(usuarioEmail);
    }
}
