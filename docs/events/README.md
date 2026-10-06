# Eventos de pagos-service

Contrato del bus de eventos (RabbitMQ) segun el ROADMAP del proyecto (seccion "Contrato de eventos"). Cada evento va en el envelope comun (`eventId`, `eventType`, `eventVersion`, `occurredAt`, `producer`, `correlationId`, `payload`). El `correlationId` es el id del checkout de `carrito-service` y se propaga por toda la cadena.

## Publica (productor)

Exchange `pagos.events` (topic), routing key = `eventType`. Se publican con el patron *outbox*: el evento se guarda en la tabla `eventos_salida` en la misma transaccion que el pago, y `PublicadorOutbox` lo envia despues con confirmacion de RabbitMQ y `mandatory` (si ninguna cola lo recibe, queda pendiente y se reintenta). La entrega es at-least-once, asi que todo consumidor debe ser idempotente por `eventId`. El `eventId` es determinista (tipo + id del pago).

- **`pago.aprobado`** — [pago.aprobado.schema.json](./pago.aprobado.schema.json). Consumido por `pedidos-service` (pasa el pedido a PAGADO) y `carrito-service` (descuenta del carrito los items y cantidades comprados).
- **`pago.rechazado`** — [pago.rechazado.schema.json](./pago.rechazado.schema.json). Consumido por `pedidos-service`, que solo lo registra (el pedido sigue en CREADO; reintentar el pago esta fuera de alcance por ahora).

## Consume (consumidor)

- **`pedido.creado`** — schema de referencia en `pedidos-service/docs/events/pedido.creado.schema.json` (la fuente de verdad es el productor). Exchange `pedidos.events`, cola `pagos.pedido-creado` (con `pagos.pedido-creado.dlq`). Validaciones antes de pagar: campos obligatorios, tamanos maximos, moneda unica y total igual a la suma de los items.

## Errores y reintentos

Un evento que no se puede procesar nunca se reencola sin limite. Si es irrecuperable (JSON ilegible, payload invalido, regla de dominio violada) va a la DLQ de inmediato. Si el fallo es transitorio (por ejemplo, la base de datos no responde) se reintenta 3 veces tras el primer intento (esperas de 1 s, 2 s y 4 s) y luego va a la DLQ.

## Idempotencia

Un `eventId` ya visto se descarta (tabla `eventos_procesados`) y un pedido solo se paga una vez (`pagos.pedido_id` es unico). Dos copias simultaneas del mismo evento producen un solo pago.
