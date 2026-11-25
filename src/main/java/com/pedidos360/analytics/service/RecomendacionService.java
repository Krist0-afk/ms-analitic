package com.pedidos360.analytics.service;

import com.pedidos360.analytics.catalogo.CatalogoCliente;
import com.pedidos360.analytics.dto.InteraccionEvent;
import com.pedidos360.analytics.dto.ProductoInfo;
import com.pedidos360.analytics.dto.Recomendacion;
import com.pedidos360.analytics.perfil.PerfilRepository;
import com.pedidos360.analytics.perfil.PerfilUsuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Arma el perfil de navegación con cada evento y recomienda productos parecidos
// a los que el usuario consultó o compró (misma categoría).
// - El interés de compra crece con la cantidad (logarítmico: 80 unidades pesan más que 1, sin aplastar todo).
// - Cada interacción baja un poco el interés de las otras categorías, para que lo reciente cambie las
//   sugerencias; la categoría de la interacción nunca baja.
// - Diversidad: como máximo MAX_POR_CATEGORIA productos de una misma categoría en la lista.
// - En este front CARRITO_AGREGADO llega al confirmar la compra, así que lo ya comprado no se excluye:
//   baja en el orden, y primero aparecen los productos de la misma categoría que todavía no compró.
@Service
public class RecomendacionService {

    private static final Logger log = LoggerFactory.getLogger(RecomendacionService.class);

    static final double PUNTOS_VISTO = 1;
    static final double PUNTOS_CARRITO = 3;
    static final double PUNTOS_QUITADO = -1;
    static final double DECAIMIENTO = 0.9;
    static final double CASTIGO_VISTO = 0.4;
    static final double CASTIGO_COMPRADO = 0.6;
    static final int MAX_POR_CATEGORIA = 2;

    private final PerfilRepository perfiles;
    private final CatalogoCliente catalogo;
    private final MetricasService metricas;

    public RecomendacionService(PerfilRepository perfiles, CatalogoCliente catalogo, MetricasService metricas) {
        this.perfiles = perfiles;
        this.catalogo = catalogo;
        this.metricas = metricas;
    }

    // Los eventos de un usuario llegan en orden (misma partición), así que no hay carreras por perfil
    public void procesar(InteraccionEvent evento) {
        evento.validar();
        if (evento.anonimo()) {
            return;
        }
        PerfilUsuario perfil = perfiles.buscar(evento.usuarioEmail()).orElseGet(() -> new PerfilUsuario(evento.usuarioEmail()));
        if (perfil.yaAplicado(evento.eventoId())) {
            log.debug("Evento {} ya aplicado al perfil de {}", evento.eventoId(), evento.usuarioEmail());
            return;
        }

        String categoria = evento.categoria() != null ? evento.categoria()
                : catalogo.producto(evento.productoId()).map(ProductoInfo::categoria).orElse(null);
        switch (evento.tipo()) {
            case InteraccionEvent.PRODUCTO_VISTO -> {
                perfil.decaerSalvo(categoria, DECAIMIENTO);
                perfil.sumarInteres(categoria, PUNTOS_VISTO);
                perfil.productoVisto(evento.productoId());
            }
            case InteraccionEvent.CARRITO_AGREGADO -> {
                perfil.decaerSalvo(categoria, DECAIMIENTO);
                perfil.sumarInteres(categoria, puntosPorCantidad(evento.cantidad()));
                perfil.agregadoAlCarrito(evento.productoId());
            }
            case InteraccionEvent.CARRITO_QUITADO -> {
                perfil.sumarInteres(categoria, PUNTOS_QUITADO);
                perfil.quitadoDelCarrito(evento.productoId());
            }
            default -> {
                // Cupones y envíos no cambian el interés por categoría, pero quedan en el historial
            }
        }
        perfil.registrarEvento(evento.eventoId(), evento.ocurridoEn());
        perfiles.guardar(perfil);
        perfiles.guardarEvento(new InteraccionEvent(evento.eventoId(), evento.tipo(), evento.usuarioEmail(),
                evento.productoId(), evento.nombreProducto(), categoria, evento.cantidad(), evento.valor(),
                evento.origen(), evento.ocurridoEn()));
    }

    // 1 unidad = 3 puntos, 10 = 9,9 y 80 = 16,1
    static double puntosPorCantidad(Integer cantidad) {
        int unidades = cantidad == null || cantidad < 1 ? 1 : cantidad;
        return PUNTOS_CARRITO * (1 + Math.log(unidades));
    }

    public List<Recomendacion> recomendar(String usuarioEmail, int limite) {
        PerfilUsuario perfil = perfiles.buscar(usuarioEmail).orElseGet(() -> new PerfilUsuario(usuarioEmail));
        Map<Long, Long> popularidad = metricas.vistasPorProducto();
        long maxVistas = popularidad.values().stream().mapToLong(Long::longValue).max().orElse(1);
        double maxInteres = perfil.getCategorias().values().stream().mapToDouble(Double::doubleValue).max().orElse(1);

        List<Recomendacion> ordenadas = catalogo.todos().stream()
                .filter(p -> p.stock() == null || p.stock() > 0)
                .map(p -> {
                    double interes = perfil.getCategorias().getOrDefault(p.categoria(), 0.0) / maxInteres;
                    double popular = popularidad.getOrDefault(p.id(), 0L) / (double) maxVistas;
                    // Lo visto o comprado baja: se busca mostrar algo parecido pero nuevo
                    double yaVisto = perfil.getVistos().contains(p.id()) ? CASTIGO_VISTO : 0;
                    double comprado = perfil.getEnCarrito().contains(p.id()) ? CASTIGO_COMPRADO : 0;
                    double puntaje = 2 * interes + 0.5 * popular - Math.max(yaVisto, comprado);
                    String motivo = interes > 0 ? "Porque te interesa " + p.categoria() : "Popular en Pedidos360";
                    return new Recomendacion(p.id(), p.nombre(), p.categoria(), p.precio(), motivo, Math.round(puntaje * 100) / 100.0);
                })
                .sorted(Comparator.comparingDouble(Recomendacion::puntaje).reversed().thenComparing(Recomendacion::productoId))
                .toList();
        return diversificar(ordenadas, limite);
    }

    // Toma las mejores respetando el máximo por categoría; si no alcanza, completa con las que quedaron fuera
    static List<Recomendacion> diversificar(List<Recomendacion> ordenadas, int limite) {
        List<Recomendacion> elegidas = new ArrayList<>();
        List<Recomendacion> sobrantes = new ArrayList<>();
        Map<String, Integer> porCategoria = new HashMap<>();
        for (Recomendacion r : ordenadas) {
            if (elegidas.size() == limite) {
                break;
            }
            int usadas = porCategoria.getOrDefault(r.categoria(), 0);
            if (usadas < MAX_POR_CATEGORIA) {
                elegidas.add(r);
                porCategoria.put(r.categoria(), usadas + 1);
            } else {
                sobrantes.add(r);
            }
        }
        for (Recomendacion r : sobrantes) {
            if (elegidas.size() == limite) {
                break;
            }
            elegidas.add(r);
        }
        return elegidas;
    }

    public List<Map<String, Object>> historial(String usuarioEmail, int limite) {
        return perfiles.historial(usuarioEmail, limite);
    }

    public Map<String, Object> perfil(String usuarioEmail) {
        PerfilUsuario perfil = perfiles.buscar(usuarioEmail).orElseGet(() -> new PerfilUsuario(usuarioEmail));
        return Map.of("usuario", usuarioEmail, "intereses", perfil.getCategorias(), "vistosRecientes", perfil.getVistos(),
                "totalEventos", perfil.getTotalEventos(), "almacenamiento", perfiles.almacenamiento());
    }
}
