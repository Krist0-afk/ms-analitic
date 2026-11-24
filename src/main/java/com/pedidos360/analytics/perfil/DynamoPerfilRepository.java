package com.pedidos360.analytics.perfil;

import com.pedidos360.analytics.dto.InteraccionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// Perfiles e historial de navegación en Amazon DynamoDB.
//   pedidos360-perfiles: clave usuarioEmail; intereses por categoría y productos recientes
//   pedidos360-eventos:  clave usuarioEmail + clave (fecha#eventoId); se borra solo con TTL
@Repository
@ConditionalOnProperty(name = "app.dynamodb.habilitado", havingValue = "true")
public class DynamoPerfilRepository implements PerfilRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoPerfilRepository.class);

    private final DynamoDbClient dynamo;
    private final String tablaPerfiles;
    private final String tablaEventos;
    private final int diasHistorial;

    public DynamoPerfilRepository(DynamoDbClient dynamo,
                                  @Value("${app.dynamodb.tabla-perfiles}") String tablaPerfiles,
                                  @Value("${app.dynamodb.tabla-eventos}") String tablaEventos,
                                  @Value("${app.dynamodb.dias-historial}") int diasHistorial) {
        this.dynamo = dynamo;
        this.tablaPerfiles = tablaPerfiles;
        this.tablaEventos = tablaEventos;
        this.diasHistorial = diasHistorial;
    }

    // Crea las tablas si no existen (modo bajo demanda: se paga por uso, sin capacidad fija)
    @PostConstruct
    void crearTablas() {
        crearTabla(tablaPerfiles, "usuarioEmail", null);
        crearTabla(tablaEventos, "usuarioEmail", "clave");
        try {
            dynamo.updateTimeToLive(UpdateTimeToLiveRequest.builder().tableName(tablaEventos)
                    .timeToLiveSpecification(TimeToLiveSpecification.builder().attributeName("expiraEn").enabled(true).build())
                    .build());
        } catch (RuntimeException e) {
            log.debug("TTL de {} ya configurado: {}", tablaEventos, e.getMessage());
        }
    }

    private void crearTabla(String nombre, String particion, String orden) {
        try {
            CreateTableRequest.Builder req = CreateTableRequest.builder().tableName(nombre).billingMode(BillingMode.PAY_PER_REQUEST);
            if (orden == null) {
                req.attributeDefinitions(AttributeDefinition.builder().attributeName(particion).attributeType(ScalarAttributeType.S).build())
                        .keySchema(KeySchemaElement.builder().attributeName(particion).keyType(KeyType.HASH).build());
            } else {
                req.attributeDefinitions(
                                AttributeDefinition.builder().attributeName(particion).attributeType(ScalarAttributeType.S).build(),
                                AttributeDefinition.builder().attributeName(orden).attributeType(ScalarAttributeType.S).build())
                        .keySchema(
                                KeySchemaElement.builder().attributeName(particion).keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName(orden).keyType(KeyType.RANGE).build());
            }
            dynamo.createTable(req.build());
            dynamo.waiter().waitUntilTableExists(b -> b.tableName(nombre));
            log.info("Tabla DynamoDB '{}' creada", nombre);
        } catch (ResourceInUseException e) {
            log.info("Tabla DynamoDB '{}' ya existe", nombre);
        }
    }

    @Override
    public Optional<PerfilUsuario> buscar(String usuarioEmail) {
        Map<String, AttributeValue> item = dynamo.getItem(b -> b.tableName(tablaPerfiles)
                .key(Map.of("usuarioEmail", s(usuarioEmail)))).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Double> categorias = item.getOrDefault("categorias", AttributeValue.fromM(Map.of())).m().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> Double.parseDouble(e.getValue().n()), (a, b) -> a, LinkedHashMap::new));
        return Optional.of(new PerfilUsuario(usuarioEmail, categorias,
                numeros(item.get("vistos")), numeros(item.get("enCarrito")),
                item.containsKey("eventos") ? item.get("eventos").l().stream().map(AttributeValue::s).toList() : List.of(),
                item.containsKey("totalEventos") ? Long.parseLong(item.get("totalEventos").n()) : 0,
                item.containsKey("actualizadoEn") ? Instant.parse(item.get("actualizadoEn").s()) : null));
    }

    @Override
    public void guardar(PerfilUsuario perfil) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("usuarioEmail", s(perfil.getUsuarioEmail()));
        item.put("categorias", AttributeValue.fromM(perfil.getCategorias().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> n(e.getValue())))));
        item.put("vistos", AttributeValue.fromL(perfil.getVistos().stream().map(DynamoPerfilRepository::n).toList()));
        item.put("enCarrito", AttributeValue.fromL(perfil.getEnCarrito().stream().map(DynamoPerfilRepository::n).toList()));
        item.put("eventos", AttributeValue.fromL(perfil.getEventos().stream().map(DynamoPerfilRepository::s).toList()));
        item.put("totalEventos", n(perfil.getTotalEventos()));
        if (perfil.getActualizadoEn() != null) {
            item.put("actualizadoEn", s(perfil.getActualizadoEn().toString()));
        }
        dynamo.putItem(b -> b.tableName(tablaPerfiles).item(item));
    }

    @Override
    public void guardarEvento(InteraccionEvent evento) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("usuarioEmail", s(evento.usuarioEmail()));
        item.put("clave", s(evento.ocurridoEn() + "#" + evento.eventoId()));
        item.put("tipo", s(evento.tipo()));
        if (evento.productoId() != null) {
            item.put("productoId", n(evento.productoId()));
        }
        if (evento.categoria() != null) {
            item.put("categoria", s(evento.categoria()));
        }
        if (evento.valor() != null) {
            item.put("valor", n(evento.valor()));
        }
        item.put("origen", s(String.valueOf(evento.origen())));
        item.put("expiraEn", n(evento.ocurridoEn().plus(diasHistorial, ChronoUnit.DAYS).getEpochSecond()));
        dynamo.putItem(b -> b.tableName(tablaEventos).item(item));
    }

    @Override
    public List<Map<String, Object>> historial(String usuarioEmail, int limite) {
        var respuesta = dynamo.query(QueryRequest.builder().tableName(tablaEventos)
                .keyConditionExpression("usuarioEmail = :u")
                .expressionAttributeValues(Map.of(":u", s(usuarioEmail)))
                .scanIndexForward(false)
                .limit(limite)
                .build());
        return respuesta.items().stream().map(i -> {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("tipo", i.get("tipo").s());
            fila.put("productoId", i.containsKey("productoId") ? Long.parseLong(i.get("productoId").n()) : null);
            fila.put("categoria", i.containsKey("categoria") ? i.get("categoria").s() : null);
            fila.put("ocurridoEn", i.get("clave").s().split("#")[0]);
            return fila;
        }).toList();
    }

    @Override
    public String almacenamiento() {
        return "dynamodb";
    }

    private static List<Long> numeros(AttributeValue lista) {
        return lista == null ? List.of() : lista.l().stream().map(v -> Long.parseLong(v.n())).toList();
    }

    private static AttributeValue s(String valor) {
        return AttributeValue.fromS(valor);
    }

    private static AttributeValue n(Number valor) {
        return AttributeValue.fromN(String.valueOf(valor));
    }
}
