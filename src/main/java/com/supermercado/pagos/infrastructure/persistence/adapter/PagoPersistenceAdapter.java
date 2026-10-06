package com.supermercado.pagos.infrastructure.persistence.adapter;

import com.supermercado.pagos.application.port.PagoRepositoryPort;
import com.supermercado.pagos.domain.model.EstadoPago;
import com.supermercado.pagos.domain.model.MotivoRechazo;
import com.supermercado.pagos.domain.model.Pago;
import com.supermercado.pagos.domain.model.PagoId;
import com.supermercado.pagos.infrastructure.persistence.entity.PagoEntity;
import com.supermercado.pagos.infrastructure.persistence.repository.SpringDataPagoRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PagoPersistenceAdapter implements PagoRepositoryPort {

    private final SpringDataPagoRepository springDataPagoRepository;

    @Override
    public Pago guardar(Pago pago) {
        return aDominio(springDataPagoRepository.saveAndFlush(aEntidad(pago)));
    }

    @Override
    public Optional<Pago> buscarPorId(PagoId id) {
        return springDataPagoRepository.findById(id.getValor()).map(this::aDominio);
    }

    @Override
    public List<Pago> buscarPorUsuarioId(String usuarioId) {
        return springDataPagoRepository.findByUsuarioIdOrderByCreadoEnDesc(usuarioId).stream().map(this::aDominio).toList();
    }

    @Override
    public boolean existePorPedidoId(UUID pedidoId) {
        return springDataPagoRepository.existsByPedidoId(pedidoId);
    }

    private PagoEntity aEntidad(Pago pago) {
        PagoEntity entity = new PagoEntity();
        entity.setId(pago.getId().getValor());
        entity.setPedidoId(pago.getPedidoId());
        entity.setUsuarioId(pago.getUsuarioId());
        entity.setMonto(pago.getMonto());
        entity.setMoneda(pago.getMoneda());
        entity.setEstado(pago.getEstado().name());
        entity.setMotivoRechazo(pago.getMotivoRechazo() == null ? null : pago.getMotivoRechazo().name());
        entity.setCreadoEn(pago.getCreadoEn());
        return entity;
    }

    private Pago aDominio(PagoEntity entity) {
        return Pago.reconstruir(PagoId.de(entity.getId()), entity.getPedidoId(), entity.getUsuarioId(), entity.getMonto(),
                entity.getMoneda(), EstadoPago.valueOf(entity.getEstado()),
                entity.getMotivoRechazo() == null ? null : MotivoRechazo.valueOf(entity.getMotivoRechazo()),
                entity.getCreadoEn());
    }
}
