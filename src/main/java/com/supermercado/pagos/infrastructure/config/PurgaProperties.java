package com.supermercado.pagos.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * purga.*: retencion de eventos_procesados y eventos_salida. Validado al arrancar: un plazo de 0 dias borraria todo
 * (incluida la idempotencia de eventos recien procesados), asi que el servicio se niega a arrancar con valores invalidos.
 */
@Validated
@ConfigurationProperties(prefix = "purga")
public record PurgaProperties(
        @NotBlank String cron,
        @Min(1) int eventosProcesadosDias,
        @Min(1) int eventosSalidaDias,
        @Min(1) int lote) {
}
