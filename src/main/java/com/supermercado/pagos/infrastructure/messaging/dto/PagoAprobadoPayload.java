package com.supermercado.pagos.infrastructure.messaging.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Payload de pago.aprobado; contrato en docs/events/pago.aprobado.schema.json. */
public record PagoAprobadoPayload(
        UUID pagoId,
        UUID pedidoId,
        UUID carritoId,
        String usuarioId,
        BigDecimal monto,
        String moneda,
        List<ItemDto> items
) {

    public record ItemDto(UUID productoId, int cantidad) {
    }
}
