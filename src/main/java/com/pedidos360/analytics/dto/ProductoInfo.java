package com.pedidos360.analytics.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Producto tal como lo entrega el catálogo
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductoInfo(Long id, String nombre, Double precio, Integer stock, String categoria) {
}
