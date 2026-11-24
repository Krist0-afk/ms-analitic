package com.pedidos360.analytics.perfil;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Perfil de navegación de un usuario, construido a partir de sus eventos
public class PerfilUsuario {

    static final int MAX_RECIENTES = 20;
    static final int MAX_EVENTOS_RECORDADOS = 50;

    private final String usuarioEmail;
    // Interés acumulado por categoría (ver producto suma 1, agregar al carrito suma 3)
    private final Map<String, Double> categorias;
    // Productos vistos, el más reciente primero
    private final List<Long> vistos;
    private final List<Long> enCarrito;
    // Últimos eventos aplicados: evita contar dos veces un evento reenviado
    private final List<String> eventos;
    private long totalEventos;
    private Instant actualizadoEn;

    public PerfilUsuario(String usuarioEmail) {
        this(usuarioEmail, new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0, null);
    }

    public PerfilUsuario(String usuarioEmail, Map<String, Double> categorias, List<Long> vistos, List<Long> enCarrito,
                         List<String> eventos, long totalEventos, Instant actualizadoEn) {
        this.usuarioEmail = usuarioEmail;
        this.categorias = new LinkedHashMap<>(categorias);
        this.vistos = new ArrayList<>(vistos);
        this.enCarrito = new ArrayList<>(enCarrito);
        this.eventos = new ArrayList<>(eventos);
        this.totalEventos = totalEventos;
        this.actualizadoEn = actualizadoEn;
    }

    public boolean yaAplicado(String eventoId) {
        return eventos.contains(eventoId);
    }

    public void registrarEvento(String eventoId, Instant cuando) {
        eventos.add(0, eventoId);
        recortar(eventos, MAX_EVENTOS_RECORDADOS);
        totalEventos++;
        actualizadoEn = cuando;
    }

    public void sumarInteres(String categoria, double puntos) {
        if (categoria != null) {
            categorias.merge(categoria, puntos, Double::sum);
            if (categorias.get(categoria) <= 0) {
                categorias.remove(categoria);
            }
        }
    }

    public void productoVisto(Long productoId) {
        moverAlInicio(vistos, productoId);
    }

    public void agregadoAlCarrito(Long productoId) {
        moverAlInicio(enCarrito, productoId);
    }

    public void quitadoDelCarrito(Long productoId) {
        enCarrito.remove(productoId);
    }

    private static <T> void moverAlInicio(List<T> lista, T valor) {
        if (valor != null) {
            lista.remove(valor);
            lista.add(0, valor);
            recortar(lista, MAX_RECIENTES);
        }
    }

    private static <T> void recortar(List<T> lista, int maximo) {
        while (lista.size() > maximo) {
            lista.remove(lista.size() - 1);
        }
    }

    public String getUsuarioEmail() { return usuarioEmail; }
    public Map<String, Double> getCategorias() { return categorias; }
    public List<Long> getVistos() { return vistos; }
    public List<Long> getEnCarrito() { return enCarrito; }
    public List<String> getEventos() { return eventos; }
    public long getTotalEventos() { return totalEventos; }
    public Instant getActualizadoEn() { return actualizadoEn; }
}
