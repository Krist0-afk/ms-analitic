package com.pedidos360.analytics.catalogo;

import com.pedidos360.analytics.dto.ProductoInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Copia local del catálogo (nombre, categoría, precio, stock) que se refresca cada pocos minutos
@Component
public class CatalogoCliente {

    private static final Logger log = LoggerFactory.getLogger(CatalogoCliente.class);

    private final RestClient restClient;
    private final Map<Long, ProductoInfo> productos = new ConcurrentHashMap<>();

    public CatalogoCliente(RestClient.Builder builder, @Value("${app.catalogo.url}") String url) {
        this.restClient = builder.baseUrl(url).build();
    }

    @Scheduled(initialDelay = 5000, fixedDelayString = "${app.catalogo.refresco-ms}")
    public void refrescar() {
        try {
            List<ProductoInfo> lista = restClient.get().uri("/public").retrieve()
                    .body(new ParameterizedTypeReference<List<ProductoInfo>>() {});
            if (lista != null && !lista.isEmpty()) {
                lista.forEach(p -> productos.put(p.id(), p));
                log.info("Catálogo actualizado: {} productos", lista.size());
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo leer el catálogo, se mantiene la copia anterior ({} productos): {}", productos.size(), e.getMessage());
        }
    }

    public Optional<ProductoInfo> producto(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        if (productos.isEmpty()) {
            refrescar();
        }
        return Optional.ofNullable(productos.get(id));
    }

    public Collection<ProductoInfo> todos() {
        if (productos.isEmpty()) {
            refrescar();
        }
        return productos.values();
    }

    // Lo usan las pruebas y el consumidor cuando el evento ya trae los datos del producto
    public void recordar(ProductoInfo producto) {
        if (producto.id() != null) {
            productos.merge(producto.id(), producto, (viejo, nuevo) -> new ProductoInfo(viejo.id(),
                    nuevo.nombre() != null ? nuevo.nombre() : viejo.nombre(),
                    nuevo.precio() != null ? nuevo.precio() : viejo.precio(),
                    viejo.stock(),
                    nuevo.categoria() != null ? nuevo.categoria() : viejo.categoria()));
        }
    }
}
