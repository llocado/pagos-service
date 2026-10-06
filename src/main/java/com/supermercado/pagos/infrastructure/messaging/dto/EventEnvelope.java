package com.supermercado.pagos.infrastructure.messaging.dto;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Envelope comun a todo evento del sistema (ver "Contrato de eventos" en el
 * ROADMAP). El payload se deja como JsonNode y se convierte al DTO especifico
 * de cada evento en el listener correspondiente, para no acoplar este tipo
 * generico a un tipo de payload en particular.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String producer,
        UUID correlationId,
        JsonNode payload
) {
}
