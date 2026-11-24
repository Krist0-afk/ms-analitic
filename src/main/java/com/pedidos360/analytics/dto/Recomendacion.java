package com.pedidos360.analytics.dto;

public record Recomendacion(Long productoId, String nombre, String categoria, Double precio, String motivo, double puntaje) {
}
