# ms-analytics-recommendations

Analítica de navegación, recomendaciones y métricas en tiempo real de Pedidos360.
Spring Boot 4.1 · Java 21 · puerto **8091** · Amazon DynamoDB.

## Consumidores Kafka

Tres consumer groups, cada uno recibe todos los eventos de su tópico y hace su propio trabajo
(3 hilos por grupo, uno por partición):

| Grupo | Tópico | Qué hace |
|---|---|---|
| `analytics-recomendaciones` | `pedidos360.interacciones` | Actualiza el perfil del usuario (interés por categoría, productos vistos y en carrito) en DynamoDB |
| `analytics-metricas` | `pedidos360.interacciones` | Clics por minuto, productos más vistos, usuarios activos |
| `analytics-pagos` | `pedidos360.pagos` | Pagos aprobados, rechazados y bloqueados; alertas de fraude |

Errores: 3 reintentos y luego el mensaje va a `<tópico>.DLT` con un log del motivo. Los eventos
incompletos o mal formados van directo al DLT. Un evento repetido (mismo `eventoId`) no se cuenta
dos veces.

## Recomendaciones

Se sugieren productos de las categorías que el usuario consultó o agregó al carrito, sin repetir
lo que ya está en su carrito ni productos sin stock. Sin historial, se muestra lo más popular.

## Almacenamiento (DynamoDB)

- `pedidos360-perfiles` (clave `usuarioEmail`): intereses y productos recientes.
- `pedidos360-eventos` (clave `usuarioEmail` + `clave`): historial; se borra solo con TTL a los 30 días.

Las tablas se crean al arrancar. En la EC2 las credenciales vienen del rol de la instancia
(`LabInstanceProfile`). Con `DYNAMODB_ENABLED=false` los perfiles se guardan en memoria.

## Endpoints (`/api/v1/analytics`)

| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/recomendaciones?limite=4` | Productos sugeridos para el usuario del token |
| GET | `/perfil` | Intereses y productos vistos |
| GET | `/historial` | Últimos eventos del usuario |
| GET | `/metricas` | Métricas actuales (rol Admin) |
| WS | `/ws` | STOMP: `/topic/metricas` y `/topic/alertas` (rol Admin, JWT en el CONNECT) |
| GET | `/health` | Estado (público) |

## Variables de entorno

| Variable | Ejemplo |
|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka1:29092,kafka2:29092,kafka3:29092` |
| `CATALOGO_URL` | `http://<ip-catalogo>:8081/api/v1/catalogo` |
| `DYNAMODB_ENABLED`, `AWS_REGION` | `true`, `us-east-1` |
| `COGNITO_ISSUER`, `COGNITO_CLIENT_ID` | Pool de AWS Cognito |
| `CORS_ALLOWED_ORIGINS` | `https://<ip-del-front>` |

## Comandos

```bash
./mvnw spring-boot:run
./mvnw test
docker build -t pedidos360-analytics .
```
