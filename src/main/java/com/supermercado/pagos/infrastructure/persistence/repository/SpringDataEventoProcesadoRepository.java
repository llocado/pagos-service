package com.supermercado.pagos.infrastructure.persistence.repository;

import com.supermercado.pagos.infrastructure.persistence.entity.EventoProcesadoEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataEventoProcesadoRepository extends JpaRepository<EventoProcesadoEntity, UUID> {
}
