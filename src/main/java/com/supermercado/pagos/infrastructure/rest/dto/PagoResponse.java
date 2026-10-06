package com.supermercado.pagos.infrastructure.rest.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PagoResponse(
        String id,
        String pedidoId,
        String usuarioId,
        BigDecimal monto,
        String moneda,
        String estado,
        String motivoRechazo,
        Instant creadoEn
) {
}
