package com.pedidos360.analytics.service;

import com.pedidos360.analytics.catalogo.CatalogoCliente;
import com.pedidos360.analytics.dto.InteraccionEvent;
import com.pedidos360.analytics.dto.PagoEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricasServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-21T15:30:20Z");

    private SimpMessagingTemplate websocket;
    private MetricasService metricas;

    @BeforeEach
    void preparar() {
        CatalogoCliente catalogo = mock(CatalogoCliente.class);
        when(catalogo.producto(any())).thenReturn(Optional.empty());
        websocket = mock(SimpMessagingTemplate.class);
        metricas = new MetricasService(catalogo, websocket, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private InteraccionEvent visto(String id, long producto, Instant cuando) {
        return new InteraccionEvent(id, InteraccionEvent.PRODUCTO_VISTO, "ana@duocuc.cl", producto, "P" + producto, "C", 1, 1.0, "t", cuando);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cuentaClicsPorMinutoYProductosMasVistos() {
        metricas.registrarInteraccion(visto("a", 1, AHORA));
        metricas.registrarInteraccion(visto("b", 1, AHORA));
        metricas.registrarInteraccion(visto("c", 2, AHORA.minusSeconds(60)));
        // Reenvío del mismo evento: no suma
        metricas.registrarInteraccion(visto("a", 1, AHORA));

        Map<String, Object> s = metricas.snapshot();
        List<Map<String, Object>> porMinuto = (List<Map<String, Object>>) s.get("clicsPorMinuto");
        List<Map<String, Object>> top = (List<Map<String, Object>>) s.get("productosMasVistos");

        assertThat(porMinuto).hasSize(MetricasService.MINUTOS_GRAFICO);
        assertThat(porMinuto.get(porMinuto.size() - 1).get("total")).isEqualTo(2L);
        assertThat(porMinuto.get(porMinuto.size() - 2).get("total")).isEqualTo(1L);
        assertThat(top.get(0)).containsEntry("productoId", 1L).containsEntry("vistas", 2L);
        assertThat(s.get("totalEventos")).isEqualTo(3L);
        assertThat(s.get("usuariosActivos")).isEqualTo(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagosBloqueadosGeneranAlertaInmediata() {
        metricas.registrarPago(new PagoEvent("pago-1", 1L, 9L, "ana@duocuc.cl", 7990.0, "1111", "APROBADO", null, AHORA));
        metricas.registrarPago(new PagoEvent("pago-2", 2L, 9L, "ana@duocuc.cl", 7990.0, "1111", "BLOQUEADO_FRAUDE", "4 veces en 10 s", AHORA));

        Map<String, Object> pagos = (Map<String, Object>) metricas.snapshot().get("pagos");
        List<Map<String, Object>> alertas = (List<Map<String, Object>>) metricas.snapshot().get("alertasFraude");

        assertThat(pagos).containsEntry("aprobados", 1L).containsEntry("bloqueados", 1L).containsEntry("montoAprobado", 7990L);
        assertThat(alertas).hasSize(1);
        assertThat(alertas.get(0)).containsEntry("tarjeta", "****1111").containsEntry("usuario", "a***@duocuc.cl");
        verify(websocket).convertAndSend(eq("/topic/alertas"), any(Object.class));
    }
}
