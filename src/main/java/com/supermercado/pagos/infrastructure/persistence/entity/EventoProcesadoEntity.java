package com.supermercado.pagos.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Registro de idempotencia generico: un evento consumido (identificado por
 * su eventId del envelope) se marca aqui, para no reprocesarlo si RabbitMQ
 * lo reentrega (entrega at-least-once, ver "Contrato de eventos" en el
 * ROADMAP). Pensada para reutilizarse con cualquier listener del servicio.
 */
@Entity
@Table(name = "eventos_procesados")
@Getter
@Setter
@NoArgsConstructor
public class EventoProcesadoEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "procesado_en", nullable = false)
    private Instant procesadoEn;
}
