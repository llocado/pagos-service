package com.supermercado.pagos.infrastructure.messaging.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** pagos.outbox.*: frecuencia del publicador, tamano del lote y espera de la confirmacion de RabbitMQ. */
@ConfigurationProperties(prefix = "pagos.outbox")
public record OutboxProperties(Duration intervalo, int lote, Duration esperaConfirmacion) {
}
