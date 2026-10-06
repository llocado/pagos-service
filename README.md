# pagos-service

Microservicio de pagos del proyecto supermercado. Arquitectura hexagonal, mismo estilo que `productos-service`,
`carrito-service` y `pedidos-service`: casos de uso en `application/usecase`, puertos en `application/port`,
adaptadores en `infrastructure/*`.

El diseno completo del proyecto (decisiones de arquitectura, mapa de servicios, contrato de eventos) esta en
`docs/ROADMAP.md` del repo `app-productos` (documento interno, no publicado).

## Que hace

Cuando `pedidos-service` publica `pedido.creado`, este servicio **simula** el pago (no hay banco real):

- Si el total del pedido **no supera** el umbral (`pagos.simulacion.umbral-rechazo`, 100000 CLP por defecto) el pago
  queda `APROBADO` y se publica `pago.aprobado` (lo consumen `pedidos-service`, que marca el pedido como PAGADO, y
  `carrito-service`, que descuenta del carrito lo comprado).
- Si lo supera queda `RECHAZADO` (`MONTO_EXCEDE_LIMITE`) y se publica `pago.rechazado`; el pedido sigue en CREADO.

La regla es determinista a proposito: se puede probar el rechazo en tests, en Bruno y en las pruebas de fallo.

## Decisiones de diseno propias de este servicio

- **Los pagos no se crean por REST.** Solo nacen al consumir `pedido.creado`. La API es de solo lectura
  (`GET /api/pagos`, `GET /api/pagos/{id}`) y el id de usuario sale siempre del claim `sub` del JWT.
- **Outbox transaccional.** El pago, el evento de salida (`eventos_salida`) y la marca de evento procesado se confirman
  en **una sola transaccion**; `PublicadorOutbox` envia despues los eventos pendientes con confirmacion de RabbitMQ y
  `mandatory`. Un evento solo se da por publicado si RabbitMQ lo confirma **y** alguna cola lo recibio: si no hay cola
  enlazada o RabbitMQ no responde, queda pendiente y se reintenta. Esto evita el defecto de publicar antes del commit.
- **Idempotencia doble:** por `eventId` (`eventos_procesados`) y por pedido (`pagos.pedido_id` es unico). Dos copias
  simultaneas del mismo evento producen un solo pago.
- **Validacion antes de pagar:** campos obligatorios, tamanos maximos, una sola moneda y total igual a la suma de los items.
- **Politica de errores del listener:** payload invalido o regla de dominio violada => DLQ de inmediato; fallo
  transitorio => 1 intento + 3 reintentos (1 s, 2 s, 4 s) y luego DLQ. Nunca se reencola en bucle.
- **Sin reintento de pago** por ahora: un pago rechazado no se vuelve a intentar (pendiente documentado).

## Requisitos para correr localmente

1. `supermercado-infra` levantado (Keycloak + RabbitMQ): `docker compose up -d` en ese repo.
2. Este repo tiene su propio Postgres (puerto **5435**): `docker compose up -d` en este repo.

```bash
docker compose up -d          # Postgres de este servicio
./gradlew bootRun             # arranca en localhost:8083
```

Variables utiles: `PAGOS_UMBRAL_RECHAZO`, `PAGOS_OUTBOX_INTERVALO` (1s), `PAGOS_OUTBOX_LOTE` (50),
`PAGOS_OUTBOX_ESPERA_CONFIRMACION` (5s), `RABBITMQ_CONNECTION_TIMEOUT` (5s), `DB_CONNECTION_TIMEOUT` (5000 ms),
`JPA_SHOW_SQL` (false).

## Endpoints

Todos requieren `Authorization: Bearer <token>` con rol `cliente` (ver `bruno/Pagos/00 - Obtener Token.bru`).

| Metodo | Ruta | Que hace |
|---|---|---|
| GET | `/api/pagos` | Listar los pagos del usuario autenticado (mas recientes primero) |
| GET | `/api/pagos/{id}` | Ver un pago (404 si no existe o es de otro usuario) |

El contrato de los eventos esta en `docs/events/`.

## Tests

```bash
./gradlew test              # unitarios (dominio, politica de pago, casos de uso, listener, REST)
./gradlew intTest           # integracion con Testcontainers: Postgres + RabbitMQ (requiere Docker)
./gradlew jacocoFullReport  # cobertura test + intTest
./gradlew pitest            # tests de mutacion (solo unitarios)
```

Los tests de integracion publican eventos reales en RabbitMQ, validan lo publicado contra los JSON Schema de
`docs/events/` y cubren: pago aprobado y rechazado, duplicados (incluidas ocho entregas simultaneas), el outbox con y sin
cola enlazada, mensajes venenosos y fallos transitorios.
