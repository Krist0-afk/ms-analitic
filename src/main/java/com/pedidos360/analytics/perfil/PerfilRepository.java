package com.pedidos360.analytics.perfil;

import com.pedidos360.analytics.dto.InteraccionEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

// Dónde se guardan los perfiles y el historial: DynamoDB en AWS, memoria en desarrollo
public interface PerfilRepository {

    Optional<PerfilUsuario> buscar(String usuarioEmail);

    void guardar(PerfilUsuario perfil);

    void guardarEvento(InteraccionEvent evento);

    List<Map<String, Object>> historial(String usuarioEmail, int limite);

    String almacenamiento();
}
