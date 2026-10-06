package com.supermercado.pagos.infrastructure.messaging.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * DTO de deserializacion del payload de pedido.creado, armado a partir del JSON Schema que publica
 * pedidos-service (docs/events/pedido.creado.schema.json). pagos-service no importa nada de otro servicio.
 */
public record PedidoCreadoPayload(
        UUID pedidoId,
        UUID carritoId,
        String usuarioId,
        List<ItemDto> items,
        BigDecimal total
) {

    public record ItemDto(
            UUID productoId,
            String nombre,
            BigDecimal precioUnitario,
            String moneda,
            int cantidad
    ) {
    }
}
