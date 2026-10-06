package com.supermercado.pagos.application.port;

import com.supermercado.pagos.domain.model.Pago;
import com.supermercado.pagos.domain.model.PagoId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PagoRepositoryPort {

    Pago guardar(Pago pago);

    Optional<Pago> buscarPorId(PagoId id);

    List<Pago> buscarPorUsuarioId(String usuarioId);

    boolean existePorPedidoId(UUID pedidoId);
}
