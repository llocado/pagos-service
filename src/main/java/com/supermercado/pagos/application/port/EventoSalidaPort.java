package com.supermercado.pagos.application.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Registra un evento de dominio para publicarlo despues (patron outbox): se guarda en la misma
 * transaccion que el pago y un publicador lo envia a RabbitMQ con confirmacion. La capa de
 * aplicacion no conoce RabbitMQ ni el formato del mensaje.
 */
public interface EventoSalidaPort {

    record ItemComprado(UUID productoId, int cantidad) {
    }

    record PagoAprobado(UUID pagoId, UUID pedidoId, UUID carritoId, String usuarioId, BigDecimal monto,
                        String moneda, List<ItemComprado> items) {
    }

    record PagoRechazado(UUID pagoId, UUID pedidoId, String usuarioId, BigDecimal monto, String moneda,
                         String motivo) {
    }

    void registrar(PagoAprobado evento, UUID correlationId);

    void registrar(PagoRechazado evento, UUID correlationId);
}
