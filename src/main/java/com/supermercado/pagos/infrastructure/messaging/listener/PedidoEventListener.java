package com.supermercado.pagos.infrastructure.messaging.listener;

import com.supermercado.pagos.application.port.EventoSalidaPort.ItemComprado;
import com.supermercado.pagos.application.usecase.ProcesarPagoDePedidoUseCase;
import com.supermercado.pagos.application.usecase.ProcesarPagoDePedidoUseCase.Comando;
import com.supermercado.pagos.domain.exception.PagoInvalidoException;
import com.supermercado.pagos.infrastructure.messaging.config.RabbitMQConfig;
import com.supermercado.pagos.infrastructure.messaging.dto.EventEnvelope;
import com.supermercado.pagos.infrastructure.messaging.dto.PedidoCreadoPayload;
import com.supermercado.pagos.infrastructure.messaging.dto.PedidoCreadoPayload.ItemDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Politica de errores (ver spring.rabbitmq.listener.simple en application.yml):
 * - Mensaje irrecuperable (payload invalido, regla de dominio violada): DLQ de inmediato, sin reintentos.
 * - Fallo transitorio (por ejemplo, la base de datos no responde): se deja propagar; Spring reintenta
 *   1 + 3 veces con espera creciente y luego lo manda a la DLQ. Nunca se reencola en bucle.
 * - Choque de unicidad por un duplicado concurrente: se comprueba y, si ya estaba procesado, se descarta.
 * Los logs llevan eventId y correlationId, nunca el payload completo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PedidoEventListener {

    static final int MAX_ITEMS = 500;
    static final int MAX_USUARIO_ID = 100;
    private static final Pattern MONEDA = Pattern.compile("^[A-Z]{3}$");

    private final ProcesarPagoDePedidoUseCase procesarPagoDePedidoUseCase;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_PEDIDO_CREADO)
    public void escucharPedidoCreado(EventEnvelope envelope) {
        if (envelope == null || envelope.eventId() == null || envelope.payload() == null) {
            log.warn("Evento descartado a la DLQ: falta eventId o payload");
            throw new AmqpRejectAndDontRequeueException("Envelope invalido: falta eventId o payload");
        }

        UUID correlacion = envelope.correlationId() != null ? envelope.correlationId() : envelope.eventId();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlacion.toString())) {
            log.info("Evento recibido eventType={} eventId={}", envelope.eventType(), envelope.eventId());

            PedidoCreadoPayload payload = leerPayload(envelope);
            String motivo = validar(payload);
            if (motivo != null) {
                log.warn("Evento descartado a la DLQ eventId={}: payload invalido ({})", envelope.eventId(), motivo);
                throw new AmqpRejectAndDontRequeueException("Payload invalido: " + motivo);
            }
            procesar(envelope, payload, correlacion);
        }
    }

    private void procesar(EventEnvelope envelope, PedidoCreadoPayload payload, UUID correlacion) {
        List<ItemComprado> items = payload.items().stream().map(i -> new ItemComprado(i.productoId(), i.cantidad())).toList();
        Comando comando = new Comando(envelope.eventId(), correlacion, payload.pedidoId(), payload.carritoId(),
                payload.usuarioId(), payload.total(), payload.items().get(0).moneda(), items);
        try {
            procesarPagoDePedidoUseCase.execute(comando);
        } catch (PagoInvalidoException e) {
            log.warn("Evento descartado a la DLQ eventId={}: regla de dominio violada ({})", envelope.eventId(), e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Regla de dominio violada: " + e.getMessage(), e);
        } catch (DataIntegrityViolationException e) {
            if (procesarPagoDePedidoUseCase.yaProcesado(envelope.eventId(), payload.pedidoId())) {
                log.info("Duplicado concurrente descartado eventId={} pedidoId={}", envelope.eventId(), payload.pedidoId());
                return;
            }
            log.warn("Fallo de integridad al procesar evento eventId={}: {}", envelope.eventId(), e.toString());
            throw e;
        } catch (RuntimeException e) {
            log.warn("Fallo al procesar evento eventId={}: {}", envelope.eventId(), e.toString());
            throw e;
        }
    }

    private PedidoCreadoPayload leerPayload(EventEnvelope envelope) {
        try {
            return objectMapper.treeToValue(envelope.payload(), PedidoCreadoPayload.class);
        } catch (JacksonException e) {
            log.warn("Evento descartado a la DLQ eventId={}: payload no deserializable", envelope.eventId());
            throw new AmqpRejectAndDontRequeueException("Payload no deserializable", e);
        }
    }

    /** Retorna el motivo si el payload es invalido, o null si esta bien. */
    static String validar(PedidoCreadoPayload payload) {
        if (payload == null) return "payload nulo";
        String motivo = validarCabecera(payload);
        if (motivo != null) return motivo;
        motivo = validarItems(payload.items());
        return motivo != null ? motivo : validarTotal(payload);
    }

    private static String validarCabecera(PedidoCreadoPayload payload) {
        if (payload.pedidoId() == null) return "falta pedidoId";
        if (payload.carritoId() == null) return "falta carritoId";
        if (payload.usuarioId() == null || payload.usuarioId().isBlank() || payload.usuarioId().length() > MAX_USUARIO_ID) {
            return "usuarioId ausente o demasiado largo";
        }
        return null;
    }

    private static String validarItems(List<ItemDto> items) {
        if (items == null || items.isEmpty()) return "sin items";
        if (items.size() > MAX_ITEMS) return "demasiados items";
        for (ItemDto item : items) {
            String motivo = validarItem(item);
            if (motivo != null) return motivo;
        }
        return items.stream().map(ItemDto::moneda).distinct().count() > 1 ? "items con monedas distintas" : null;
    }

    private static String validarItem(ItemDto item) {
        if (item == null) return "item nulo";
        if (item.productoId() == null) return "item sin productoId";
        if (item.precioUnitario() == null || item.precioUnitario().signum() < 0) return "item con precio invalido";
        if (item.moneda() == null || !MONEDA.matcher(item.moneda()).matches()) return "item con moneda invalida";
        if (item.cantidad() <= 0) return "item con cantidad invalida";
        return null;
    }

    /** El total debe ser positivo y coincidir con la suma de los items: un evento incoherente no se paga. */
    private static String validarTotal(PedidoCreadoPayload payload) {
        if (payload.total() == null || payload.total().signum() <= 0) return "total invalido";
        BigDecimal suma = payload.items().stream()
                .map(i -> i.precioUnitario().multiply(BigDecimal.valueOf(i.cantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return suma.compareTo(payload.total()) == 0 ? null : "el total no coincide con la suma de los items";
    }
}
