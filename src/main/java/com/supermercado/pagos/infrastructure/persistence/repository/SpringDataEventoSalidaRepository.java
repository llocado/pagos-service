package com.supermercado.pagos.infrastructure.persistence.repository;

import com.supermercado.pagos.infrastructure.persistence.entity.EventoSalidaEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;

public interface SpringDataEventoSalidaRepository extends JpaRepository<EventoSalidaEntity, UUID> {

    /**
     * Toma y bloquea los eventos pendientes mas antiguos. SKIP LOCKED evita que dos publicadores
     * (por ejemplo, dos instancias del servicio) tomen los mismos eventos.
     */
    @Query(value = "select * from eventos_salida where publicado_en is null order by creado_en limit :lote for update skip locked",
            nativeQuery = true)
    List<EventoSalidaEntity> bloquearPendientes(@Param("lote") int lote);

    long countByPublicadoEnIsNull();

    /** Solo eventos ya publicados (publicado_en no nulo), en lotes. Los pendientes nunca se borran. */
    @Modifying
    @Query(value = "delete from eventos_salida where id in "
            + "(select id from eventos_salida where publicado_en is not null and publicado_en < :limite limit :lote)",
            nativeQuery = true)
    int eliminarPublicadosAnterioresA(@Param("limite") Instant limite, @Param("lote") int lote);
}
