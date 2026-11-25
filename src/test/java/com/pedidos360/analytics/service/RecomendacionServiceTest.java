package com.pedidos360.analytics.service;

import com.pedidos360.analytics.catalogo.CatalogoCliente;
import com.pedidos360.analytics.dto.InteraccionEvent;
import com.pedidos360.analytics.dto.ProductoInfo;
import com.pedidos360.analytics.dto.Recomendacion;
import com.pedidos360.analytics.perfil.MemoriaPerfilRepository;
import com.pedidos360.analytics.perfil.PerfilUsuario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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

    // Caso real del 25-11-2026 (prueba en vivo): con la v1.1.0 Bebidas y postres bajó de 13,23 a 11,12
    // aunque el usuario vio 3 productos de esa categoría, y las 4 sugerencias quedaron en Comida rápida.
    @Test
    void verUnaCategoriaNuncaLeRestaInteresYLasSugerenciasSeDiversifican() {
        List<ProductoInfo> catalogoReal = List.of(
                new ProductoInfo(1L, "Burger 360 Doble Cheddar", 7990.0, 48, "Comida rápida"),
                new ProductoInfo(2L, "Pizza Napolitana Familiar 360", 11490.0, 28, "Comida rápida"),
                new ProductoInfo(3L, "Sushi Roll Tempura Ebi", 8990.0, 37, "Oriental y saludable"),
                new ProductoInfo(4L, "Bowl Salmón & Palta", 9490.0, 24, "Oriental y saludable"),
                new ProductoInfo(5L, "Tacos Al Pastor", 6990.0, 39, "Comida rápida"),
                new ProductoInfo(6L, "Crispy Wings BBQ & Honey", 8490.0, 0, "Comida rápida"),
                new ProductoInfo(7L, "Papel Higiénico Confort", 5990.0, 100, "Hogar"),
                new ProductoInfo(8L, "Fideos Spaghetti Carozzi", 1190.0, 200, "Abarrotes"),
                new ProductoInfo(9L, "Queso Gouda Laminado", 4890.0, 60, "Lácteos"),
                new ProductoInfo(10L, "Leche Entera (Pack x6)", 5490.0, 80, "Lácteos"),
                new ProductoInfo(11L, "Aceite Vegetal Maravilla", 2990.0, 90, "Abarrotes"),
                new ProductoInfo(12L, "Bebida Coca-Cola Zero", 1990.0, 43, "Bebidas y postres"),
                new ProductoInfo(13L, "Papas Fritas Rústicas", 3490.0, 68, "Comida rápida"),
                new ProductoInfo(14L, "Postre Brownie Fudgy", 3990.0, 4, "Bebidas y postres"));
        CatalogoCliente catalogo = mock(CatalogoCliente.class);
        when(catalogo.todos()).thenReturn(catalogoReal);
        when(catalogo.producto(any())).thenAnswer(i -> catalogoReal.stream().filter(p -> p.id().equals(i.getArgument(0))).findFirst());
        RecomendacionService real = new RecomendacionService(perfiles, catalogo,
                new MetricasService(catalogo, mock(SimpMessagingTemplate.class), Clock.systemUTC()));
        perfiles.guardar(new PerfilUsuario("gus@duocuc.cl",
                Map.of("Bebidas y postres", 13.23, "Comida rápida", 28.5, "Oriental y saludable", 8.87),
                List.of(12L, 3L), List.of(12L, 6L, 13L, 3L, 14L, 2L, 1L, 4L, 5L), List.of(), 33, null));

        for (long id : new long[] {14L, 12L, 14L}) {
            real.procesar(new InteraccionEvent("v" + id + (n++), InteraccionEvent.PRODUCTO_VISTO, "gus@duocuc.cl", id,
                    null, null, 1, 0.0, "catalogo", Instant.now()));
        }
        real.procesar(new InteraccionEvent("c" + (n++), InteraccionEvent.CARRITO_AGREGADO, "gus@duocuc.cl", 13L,
                null, null, 20, 69800.0, "compras", Instant.now()));

        Map<String, Double> intereses = perfiles.buscar("gus@duocuc.cl").orElseThrow().getCategorias();
        assertThat(intereses.get("Bebidas y postres")).isGreaterThan(13.23).isCloseTo(14.61, within(0.01));
        assertThat(intereses.get("Comida rápida")).isGreaterThan(28.5).isCloseTo(32.77, within(0.01));
        assertThat(intereses.get("Oriental y saludable")).isLessThan(8.87);

        List<Recomendacion> r = real.recomendar("gus@duocuc.cl", 4);
        assertThat(r).hasSize(4);
        assertThat(r).filteredOn(x -> x.categoria().equals("Comida rápida")).hasSize(2);
        assertThat(r).filteredOn(x -> x.categoria().equals("Bebidas y postres")).isNotEmpty()
                .allMatch(x -> x.motivo().equals("Porque te interesa Bebidas y postres"));
    }

    @Test
    void laDiversidadCompletaConLoQueSobraSiNoHayOtrasCategorias() {
        List<Recomendacion> ordenadas = List.of(
                new Recomendacion(1L, "a", "X", 1.0, "m", 3.0), new Recomendacion(2L, "b", "X", 1.0, "m", 2.0),
                new Recomendacion(3L, "c", "X", 1.0, "m", 1.0), new Recomendacion(4L, "d", "Y", 1.0, "m", 0.5));

        assertThat(RecomendacionService.diversificar(ordenadas, 3)).extracting(Recomendacion::productoId).containsExactly(1L, 2L, 4L);
        assertThat(RecomendacionService.diversificar(ordenadas, 4)).extracting(Recomendacion::productoId).containsExactly(1L, 2L, 4L, 3L);
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
