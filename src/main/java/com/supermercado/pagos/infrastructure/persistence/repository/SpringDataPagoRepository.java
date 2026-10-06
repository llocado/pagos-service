package com.supermercado.pagos.infrastructure.persistence.repository;

import com.supermercado.pagos.infrastructure.persistence.entity.PagoEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataPagoRepository extends JpaRepository<PagoEntity, UUID> {

    List<PagoEntity> findByUsuarioIdOrderByCreadoEnDesc(String usuarioId);

    boolean existsByPedidoId(UUID pedidoId);
}
