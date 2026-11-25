package com.pedidos360.analytics.service;

import com.pedidos360.analytics.catalogo.CatalogoCliente;
import com.pedidos360.analytics.dto.InteraccionEvent;
import com.pedidos360.analytics.dto.ProductoInfo;
import com.pedidos360.analytics.dto.Recomendacion;
import com.pedidos360.analytics.perfil.MemoriaPerfilRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecomendacionServiceTest {

    private static final List<ProductoInfo> CATALOGO = List.of(
            new ProductoInfo(1L, "Burger 360", 7990.0, 50, "Comida rápida"),
            new ProductoInfo(2L, "Pizza Napolitana", 11490.0, 30, "Comida rápida"),
            new ProductoInfo(3L, "Tacos Al Pastor", 6990.0, 40, "Comida rápida"),
            new ProductoInfo(4L, "Queso Gouda", 4890.0, 60, "Lácteos"),
            new ProductoInfo(5L, "Leche Entera", 5490.0, 0, "Lácteos"),
            new ProductoInfo(6L, "Fideos Spaghetti", 1190.0, 200, "Abarrotes"),
            new ProductoInfo(7L, "Aceite Maravilla", 2990.0, 90, "Abarrotes"));

    private MemoriaPerfilRepository perfiles;
    private RecomendacionService service;
    private int n;

    @BeforeEach
    void preparar() {
        CatalogoCliente catalogo = mock(CatalogoCliente.class);
        when(catalogo.todos()).thenReturn(CATALOGO);
        when(catalogo.producto(any())).thenAnswer(i -> CATALOGO.stream().filter(p -> p.id().equals(i.getArgument(0))).findFirst());
        perfiles = new MemoriaPerfilRepository();
        MetricasService metricas = new MetricasService(catalogo, mock(SimpMessagingTemplate.class), Clock.systemUTC());
        service = new RecomendacionService(perfiles, catalogo, metricas);
    }

    private InteraccionEvent evento(String tipo, Long productoId) {
        return evento(tipo, productoId, 1);
    }

    private InteraccionEvent evento(String tipo, Long productoId, int cantidad) {
        return new InteraccionEvent("e" + (n++), tipo, "ana@duocuc.cl", productoId, null, null, cantidad, 1000.0, "test", Instant.now());
    }

    @Test
    void recomiendaProductosDeLaCategoriaQueElUsuarioConsulta() {
        service.procesar(evento(InteraccionEvent.PRODUCTO_VISTO, 1L));
        service.procesar(evento(InteraccionEvent.PRODUCTO_VISTO, 1L));

        List<Recomendacion> r = service.recomendar("ana@duocuc.cl", 2);

        // Pizza y tacos son de la misma categoría que la burger vista, y no se habían visto
        assertThat(r).extracting(Recomendacion::productoId).containsExactlyInAnyOrder(2L, 3L);
        assertThat(r.get(0).motivo()).contains("Comida rápida");
    }

    @Test
    void noRecomiendaProductosSinStock() {
        service.procesar(evento(InteraccionEvent.PRODUCTO_VISTO, 4L));

        assertThat(service.recomendar("ana@duocuc.cl", 7)).extracting(Recomendacion::productoId).doesNotContain(5L);
    }

    @Test
    void unaCompraGrandeCambiaLasRecomendaciones() {
        // Venía mirando comida rápida
        for (int i = 0; i < 5; i++) {
            service.procesar(evento(InteraccionEvent.PRODUCTO_VISTO, 1L));
        }
        assertThat(service.recomendar("ana@duocuc.cl", 1).get(0).categoria()).isEqualTo("Comida rápida");

        // Compra 80 fideos: ahora lo primero es otro producto de abarrotes que todavía no compró
        service.procesar(evento(InteraccionEvent.CARRITO_AGREGADO, 6L, 80));

        List<Recomendacion> r = service.recomendar("ana@duocuc.cl", 3);
        assertThat(r.get(0).productoId()).isEqualTo(7L);
        assertThat(r.get(0).motivo()).isEqualTo("Porque te interesa Abarrotes");
        // Lo ya comprado sigue disponible, pero debajo de lo nuevo de la misma categoría
        assertThat(r).extracting(Recomendacion::productoId).containsSubsequence(7L, 6L);
    }

    @Test
    void laCantidadCompradaPesaMasQueUnaUnidad() {
        assertThat(RecomendacionService.puntosPorCantidad(1)).isEqualTo(3.0);
        assertThat(RecomendacionService.puntosPorCantidad(80)).isGreaterThan(15);
        assertThat(RecomendacionService.puntosPorCantidad(null)).isEqualTo(3.0);
    }

    @Test
    void unEventoRepetidoNoSeCuentaDosVeces() {
        InteraccionEvent e = evento(InteraccionEvent.PRODUCTO_VISTO, 1L);
        service.procesar(e);
        service.procesar(e);

        assertThat(perfiles.buscar("ana@duocuc.cl").orElseThrow().getTotalEventos()).isEqualTo(1);
    }

    @Test
    void lasVisitasAnonimasNoCreanPerfil() {
        service.procesar(new InteraccionEvent("x", InteraccionEvent.PRODUCTO_VISTO, "anonimo", 1L, null, null, 1, 1.0, "t", Instant.now()));

        assertThat(perfiles.buscar("anonimo")).isEqualTo(Optional.empty());
    }

    @Test
    void eventoIncompletoSeRechazaParaIrAlDlt() {
        assertThatThrownBy(() -> service.procesar(new InteraccionEvent(null, null, "ana@duocuc.cl", null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sinHistorialSeRecomiendaLoPopular() {
        List<Recomendacion> r = service.recomendar("nuevo@duocuc.cl", 3);

        assertThat(r).hasSize(3).allMatch(x -> x.motivo().equals("Popular en Pedidos360"));
        assertThat(service.perfil("nuevo@duocuc.cl")).containsEntry("almacenamiento", "memoria");
        assertThat(service.perfil("nuevo@duocuc.cl").get("intereses")).isEqualTo(Map.of());
    }
}
