package com.pedidos360.analytics.perfil;

import com.pedidos360.analytics.dto.InteraccionEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Perfiles en memoria (desarrollo local y pruebas)
@Repository
@ConditionalOnProperty(name = "app.dynamodb.habilitado", havingValue = "false", matchIfMissing = true)
public class MemoriaPerfilRepository implements PerfilRepository {

    private final Map<String, PerfilUsuario> perfiles = new ConcurrentHashMap<>();
    private final Map<String, List<InteraccionEvent>> eventos = new ConcurrentHashMap<>();

    @Override
    public Optional<PerfilUsuario> buscar(String usuarioEmail) {
        return Optional.ofNullable(perfiles.get(usuarioEmail));
    }

    @Override
    public void guardar(PerfilUsuario perfil) {
        perfiles.put(perfil.getUsuarioEmail(), perfil);
    }

    @Override
    public void guardarEvento(InteraccionEvent evento) {
        List<InteraccionEvent> lista = eventos.computeIfAbsent(evento.usuarioEmail(), k -> new ArrayList<>());
        synchronized (lista) {
            lista.add(0, evento);
            if (lista.size() > 200) {
                lista.remove(lista.size() - 1);
            }
        }
    }

    @Override
    public List<Map<String, Object>> historial(String usuarioEmail, int limite) {
        List<InteraccionEvent> lista = eventos.getOrDefault(usuarioEmail, List.of());
        synchronized (lista) {
            return lista.stream().limit(limite).map(e -> {
                Map<String, Object> fila = new LinkedHashMap<>();
                fila.put("tipo", e.tipo());
                fila.put("productoId", e.productoId());
                fila.put("categoria", e.categoria());
                fila.put("ocurridoEn", e.ocurridoEn().toString());
                return fila;
            }).toList();
        }
    }

    @Override
    public String almacenamiento() {
        return "memoria";
    }
}
