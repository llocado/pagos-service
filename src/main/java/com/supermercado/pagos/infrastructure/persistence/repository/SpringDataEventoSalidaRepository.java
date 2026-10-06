package com.supermercado.pagos.infrastructure.persistence.repository;

import com.supermercado.pagos.infrastructure.persistence.entity.EventoSalidaEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataEventoSalidaRepository extends JpaRepository<EventoSalidaEntity, UUID> {

    /**
     * Toma y bloquea los eventos pendientes mas antiguos. SKIP LOCKED evita que dos publicadores
     * (por ejemplo, dos instancias del servicio) tomen los mismos eventos.
     */
    @Query(value = "select * from eventos_salida where publicado_en is null order by creado_en limit :lote for update skip locked",
            nativeQuery = true)
    List<EventoSalidaEntity> bloquearPendientes(@Param("lote") int lote);

    long countByPublicadoEnIsNull();
}
