package com.supermercado.pagos.infrastructure.persistence.repository;

import com.supermercado.pagos.infrastructure.persistence.entity.EventoProcesadoEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataEventoProcesadoRepository extends JpaRepository<EventoProcesadoEntity, UUID> {

    /** Borra en lotes (la subconsulta con LIMIT evita un DELETE gigante que bloquee la tabla). */
    @Modifying
    @Query(value = "delete from eventos_procesados where event_id in "
            + "(select event_id from eventos_procesados where procesado_en < :limite limit :lote)", nativeQuery = true)
    int eliminarAnterioresA(@Param("limite") Instant limite, @Param("lote") int lote);
}
