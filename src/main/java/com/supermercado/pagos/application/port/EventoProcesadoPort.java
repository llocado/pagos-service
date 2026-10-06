package com.supermercado.pagos.application.port;

import java.util.UUID;

/**
 * Soporte de idempotencia para todo consumidor de eventos de este servicio
 * (ver "Contrato de eventos" en el ROADMAP: RabbitMQ entrega at-least-once,
 * cada consumidor debe descartar un eventId ya procesado).
 */
public interface EventoProcesadoPort {

    boolean yaFueProcesado(UUID eventId);

    void marcarComoProcesado(UUID eventId);
}
