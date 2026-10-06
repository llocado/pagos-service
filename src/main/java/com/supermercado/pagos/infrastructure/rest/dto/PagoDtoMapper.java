package com.supermercado.pagos.infrastructure.rest.dto;

import com.supermercado.pagos.domain.model.Pago;
import org.springframework.stereotype.Component;

@Component
public class PagoDtoMapper {

    public PagoResponse toResponse(Pago pago) {
        return new PagoResponse(
                pago.getId().getValor().toString(),
                pago.getPedidoId().toString(),
                pago.getUsuarioId(),
                pago.getMonto(),
                pago.getMoneda(),
                pago.getEstado().name(),
                pago.getMotivoRechazo() == null ? null : pago.getMotivoRechazo().name(),
                pago.getCreadoEn()
        );
    }
}
