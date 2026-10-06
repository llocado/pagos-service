package com.supermercado.pagos.infrastructure.messaging.outbox;

import com.supermercado.pagos.infrastructure.messaging.config.RabbitMQConfig;
import com.supermercado.pagos.infrastructure.persistence.entity.EventoSalidaEntity;
import com.supermercado.pagos.infrastructure.persistence.repository.SpringDataEventoSalidaRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publica los eventos pendientes de eventos_salida (patron outbox). Un evento solo se marca como
 * publicado si RabbitMQ lo confirma Y alguna cola lo recibio (mandatory + returns): si no hay cola
 * enlazada, o RabbitMQ no responde, queda pendiente y se reintenta en la siguiente vuelta. La
 * entrega es at-least-once (un fallo entre publicar y marcar puede duplicar), por eso todos los
 * consumidores son idempotentes por eventId.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicadorOutbox {

    private final SpringDataEventoSalidaRepository springDataEventoSalidaRepository;
    private final RabbitTemplate rabbitTemplate;
    private final PlatformTransactionManager transactionManager;
    private final OutboxProperties propiedades;

    private final AtomicInteger vueltasSinBase = new AtomicInteger();

    @Scheduled(fixedDelayString = "${pagos.outbox.intervalo}")
    public void publicarPendientes() {
        try {
            // Una transaccion por vuelta: mantiene bloqueadas (FOR UPDATE SKIP LOCKED) las filas que procesa.
            new TransactionTemplate(transactionManager).executeWithoutResult(estado -> procesarLote());
            int fallidas = vueltasSinBase.getAndSet(0);
            if (fallidas > 0) {
                log.info("Publicador del outbox recupero el acceso a la base tras {} vueltas fallidas", fallidas);
            }
        } catch (DataAccessException | TransactionException e) {
            // Con la base caida esto ocurre cada vuelta: una linea espaciada, sin stack trace (lo que importa es la causa).
            int fallidas = vueltasSinBase.incrementAndGet();
            if (registrarFallo(fallidas)) {
                log.warn("Publicador del outbox sin acceso a la base (vuelta fallida {}): {}", fallidas,
                        NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName());
            }
        }
    }

    private void procesarLote() {
        List<EventoSalidaEntity> pendientes = springDataEventoSalidaRepository.bloquearPendientes(propiedades.lote());
        for (EventoSalidaEntity evento : pendientes) {
            Resultado resultado = publicar(evento);
            if (resultado != Resultado.PUBLICADO) {
                evento.setIntentos(evento.getIntentos() + 1);
            }
            if (resultado == Resultado.BROKER_NO_DISPONIBLE) {
                // Si RabbitMQ no responde, el resto del lote fallaria igual (y esperaria el timeout por cada evento).
                break;
            }
        }
    }

    private Resultado publicar(EventoSalidaEntity evento) {
        // El MDC cubre todo el metodo, tambien los catch: asi el WARN de un fallo lleva el correlationId.
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", (evento.getCorrelationId() != null ? evento.getCorrelationId() : evento.getId()).toString())) {
            return intentarPublicar(evento);
        }
    }

    private Resultado intentarPublicar(EventoSalidaEntity evento) {
        boolean registrar = registrarFallo(evento.getIntentos() + 1);
        try {
            Message mensaje = MessageBuilder.withBody(evento.getPayload().getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setMessageId(evento.getId().toString())
                    .build();
            CorrelationData correlacion = new CorrelationData(evento.getId().toString());
            rabbitTemplate.send(RabbitMQConfig.EXCHANGE_PAGOS_EVENTS, evento.getRoutingKey(), mensaje, correlacion);

            CorrelationData.Confirm confirmacion = correlacion.getFuture()
                    .get(propiedades.esperaConfirmacion().toMillis(), TimeUnit.MILLISECONDS);
            if (confirmacion.ack() && correlacion.getReturned() == null) {
                evento.setPublicadoEn(Instant.now());
                log.info("Evento publicado eventType={} eventId={}", evento.getEventType(), evento.getId());
                return Resultado.PUBLICADO;
            }
            if (registrar) {
                log.warn("Evento no publicado, queda pendiente eventType={} eventId={} intento={} motivo={}", evento.getEventType(),
                        evento.getId(), evento.getIntentos() + 1,
                        confirmacion.ack() ? "ninguna cola lo recibio (sin binding)" : "RabbitMQ no lo confirmo");
            }
            return Resultado.PENDIENTE;
        } catch (AmqpException | ExecutionException | TimeoutException e) {
            if (registrar) {
                log.warn("Fallo al publicar, queda pendiente eventType={} eventId={} intento={}: {}", evento.getEventType(),
                        evento.getId(), evento.getIntentos() + 1, e.toString());
            }
            return Resultado.BROKER_NO_DISPONIBLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Resultado.PENDIENTE;
        }
    }

    /**
     * Registra el 1.o, 2.o, 4.o, 8.o... fallo consecutivo: con una dependencia caida, una linea por segundo
     * durante horas no aporta nada y tapa el resto del log.
     */
    static boolean registrarFallo(int numeroDeFallo) {
        return numeroDeFallo > 0 && (numeroDeFallo & (numeroDeFallo - 1)) == 0;
    }

    private enum Resultado {
        PUBLICADO, PENDIENTE, BROKER_NO_DISPONIBLE
    }
}
