package com.pedidos360.analytics.service;

import com.pedidos360.analytics.catalogo.CatalogoCliente;
import com.pedidos360.analytics.dto.InteraccionEvent;
import com.pedidos360.analytics.dto.PagoEvent;
import com.pedidos360.analytics.dto.ProductoInfo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

// Métricas en tiempo real calculadas desde los eventos de Kafka y enviadas al dashboard por WebSocket
@Service
public class MetricasService {

    static final int MINUTOS_GRAFICO = 15;
    private static final ZoneId ZONA = ZoneId.of("America/Santiago");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm").withZone(ZONA);

    private final CatalogoCliente catalogo;
    private final SimpMessagingTemplate websocket;
    private final Clock clock;

    // Eventos ya contados (los consumidores de métricas releen el tópico al arrancar)
    private final Map<String, Boolean> contados = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > 100_000;
        }
    });

    private final ConcurrentSkipListMap<Instant, AtomicLong> eventosPorMinuto = new ConcurrentSkipListMap<>();
    private final Map<Long, AtomicLong> vistasPorProducto = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> eventosPorTipo = new ConcurrentHashMap<>();
    private final Map<String, Instant> ultimaActividad = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> pagosPorEstado = new ConcurrentHashMap<>();
    private final AtomicLong montoAprobado = new AtomicLong();
    private final List<Map<String, Object>> alertas = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong totalEventos = new AtomicLong();
    private final AtomicBoolean cambios = new AtomicBoolean(true);

    public MetricasService(CatalogoCliente catalogo, SimpMessagingTemplate websocket, Clock clock) {
        this.catalogo = catalogo;
        this.websocket = websocket;
        this.clock = clock;
    }

    public void registrarInteraccion(InteraccionEvent evento) {
        evento.validar();
        if (contados.putIfAbsent(evento.eventoId(), Boolean.TRUE) != null) {
            return;
        }
        totalEventos.incrementAndGet();
        eventosPorTipo.computeIfAbsent(evento.tipo(), k -> new AtomicLong()).incrementAndGet();
        eventosPorMinuto.computeIfAbsent(evento.ocurridoEn().truncatedTo(ChronoUnit.MINUTES), k -> new AtomicLong()).incrementAndGet();
        if (!evento.anonimo()) {
            ultimaActividad.merge(evento.usuarioEmail(), evento.ocurridoEn(), (a, b) -> a.isAfter(b) ? a : b);
        }
        if (InteraccionEvent.PRODUCTO_VISTO.equals(evento.tipo()) && evento.productoId() != null) {
            vistasPorProducto.computeIfAbsent(evento.productoId(), k -> new AtomicLong()).incrementAndGet();
            catalogo.recordar(new ProductoInfo(evento.productoId(), evento.nombreProducto(), evento.valor(), null, evento.categoria()));
        }
        cambios.set(true);
    }

    public void registrarPago(PagoEvent pago) {
        pago.validar();
        if (contados.putIfAbsent(pago.eventoId(), Boolean.TRUE) != null) {
            return;
        }
        pagosPorEstado.computeIfAbsent(pago.estado(), k -> new AtomicLong()).incrementAndGet();
        if ("APROBADO".equals(pago.estado()) && pago.monto() != null) {
            montoAprobado.addAndGet(Math.round(pago.monto()));
        }
        if ("BLOQUEADO_FRAUDE".equals(pago.estado())) {
            Map<String, Object> alerta = new LinkedHashMap<>();
            alerta.put("ordenId", pago.ordenId());
            alerta.put("tarjeta", "****" + pago.ultimos4());
            alerta.put("usuario", ocultar(pago.usuarioEmail()));
            alerta.put("motivo", pago.motivo());
            alerta.put("hora", HORA.format(pago.ocurridoEn()));
            synchronized (alertas) {
                alertas.add(0, alerta);
                if (alertas.size() > 10) {
                    alertas.remove(alertas.size() - 1);
                }
            }
            // La alerta se avisa al instante, sin esperar el siguiente envío de métricas
            websocket.convertAndSend("/topic/alertas", (Object) alerta);
        }
        cambios.set(true);
    }

    public Map<Long, Long> vistasPorProducto() {
        Map<Long, Long> copia = new LinkedHashMap<>();
        vistasPorProducto.forEach((k, v) -> copia.put(k, v.get()));
        return copia;
    }

    public Map<String, Object> snapshot() {
        Instant ahora = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        List<Map<String, Object>> porMinuto = new ArrayList<>();
        for (int i = MINUTOS_GRAFICO - 1; i >= 0; i--) {
            Instant minuto = ahora.minus(i, ChronoUnit.MINUTES);
            AtomicLong total = eventosPorMinuto.get(minuto);
            porMinuto.add(Map.of("minuto", HORA.format(minuto), "total", total != null ? total.get() : 0));
        }
        eventosPorMinuto.headMap(ahora.minus(60, ChronoUnit.MINUTES)).clear();

        List<Map<String, Object>> top = vistasPorProducto.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<Long, AtomicLong> e) -> e.getValue().get()).reversed())
                .limit(5)
                .map(e -> {
                    Map<String, Object> fila = new LinkedHashMap<>();
                    fila.put("productoId", e.getKey());
                    fila.put("nombre", catalogo.producto(e.getKey()).map(ProductoInfo::nombre).orElse("Producto " + e.getKey()));
                    fila.put("vistas", e.getValue().get());
                    return fila;
                })
                .toList();

        Instant hace5 = clock.instant().minus(5, ChronoUnit.MINUTES);
        Map<String, Object> pagos = new LinkedHashMap<>();
        pagos.put("aprobados", valor(pagosPorEstado, "APROBADO"));
        pagos.put("rechazados", valor(pagosPorEstado, "RECHAZADO"));
        pagos.put("bloqueados", valor(pagosPorEstado, "BLOQUEADO_FRAUDE"));
        pagos.put("montoAprobado", montoAprobado.get());

        Map<String, Long> porTipo = new LinkedHashMap<>();
        eventosPorTipo.forEach((k, v) -> porTipo.put(k, v.get()));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("clicsPorMinuto", porMinuto);
        snapshot.put("productosMasVistos", top);
        snapshot.put("eventosPorTipo", porTipo);
        snapshot.put("pagos", pagos);
        synchronized (alertas) {
            snapshot.put("alertasFraude", List.copyOf(alertas));
        }
        snapshot.put("usuariosActivos", ultimaActividad.values().stream().filter(t -> t.isAfter(hace5)).count());
        snapshot.put("totalEventos", totalEventos.get());
        snapshot.put("actualizado", clock.instant().toString());
        return snapshot;
    }

    // Envía las métricas al dashboard como máximo una vez por segundo, y solo si cambiaron
    @Scheduled(fixedRate = 1000)
    public void publicar() {
        if (cambios.getAndSet(false)) {
            websocket.convertAndSend("/topic/metricas", (Object) snapshot());
        }
    }

    // El gráfico por minuto avanza aunque no lleguen eventos
    @Scheduled(cron = "0 * * * * *")
    public void marcarCambioDeMinuto() {
        cambios.set(true);
    }

    private static long valor(Map<String, AtomicLong> mapa, String clave) {
        AtomicLong v = mapa.get(clave);
        return v != null ? v.get() : 0;
    }

    private static String ocultar(String email) {
        if (email == null || !email.contains("@")) {
            return "desconocido";
        }
        String[] partes = email.split("@", 2);
        return partes[0].charAt(0) + "***@" + partes[1];
    }
}
