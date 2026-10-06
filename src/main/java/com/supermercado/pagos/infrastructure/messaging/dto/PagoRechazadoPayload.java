package com.supermercado.pagos.infrastructure.messaging.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Payload de pago.rechazado; contrato en docs/events/pago.rechazado.schema.json. */
public record PagoRechazadoPayload(
        UUID pagoId,
        UUID pedidoId,
        String usuarioId,
        BigDecimal monto,
        String moneda,
        String motivo
) {
}
