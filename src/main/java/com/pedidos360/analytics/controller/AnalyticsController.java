package com.pedidos360.analytics.controller;

import com.pedidos360.analytics.dto.Recomendacion;
import com.pedidos360.analytics.service.MetricasService;
import com.pedidos360.analytics.service.RecomendacionService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final RecomendacionService recomendaciones;
    private final MetricasService metricas;

    public AnalyticsController(RecomendacionService recomendaciones, MetricasService metricas) {
        this.recomendaciones = recomendaciones;
        this.metricas = metricas;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "ms-analytics-recommendations", "status", "UP");
    }

    // Productos sugeridos según lo que el usuario del token ha consultado
    @GetMapping("/recomendaciones")
    public List<Recomendacion> recomendaciones(Authentication auth, @RequestParam(defaultValue = "4") int limite) {
        return recomendaciones.recomendar(auth.getName(), Math.max(1, Math.min(limite, 12)));
    }

    @GetMapping("/perfil")
    public Map<String, Object> perfil(Authentication auth) {
        return recomendaciones.perfil(auth.getName());
    }

    @GetMapping("/historial")
    public List<Map<String, Object>> historial(Authentication auth) {
        return recomendaciones.historial(auth.getName(), 20);
    }

    // Lo mismo que se envía por WebSocket, para la carga inicial del dashboard
    @GetMapping("/metricas")
    public Map<String, Object> metricas() {
        return metricas.snapshot();
    }
}
