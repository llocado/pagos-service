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

/** Fila del outbox: el evento (envelope completo en JSON) queda guardado junto al pago hasta que se publica. */
@Entity
@Table(name = "eventos_salida")
@Getter
@Setter
@NoArgsConstructor
public class EventoSalidaEntity {

    @Id
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "routing_key", nullable = false, length = 100)
    private String routingKey;

    /** Id del checkout: el publicador lo usa para que sus logs se puedan seguir junto a los del resto del flujo. */
    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "publicado_en")
    private Instant publicadoEn;

    @Column(nullable = false)
    private int intentos;
}
