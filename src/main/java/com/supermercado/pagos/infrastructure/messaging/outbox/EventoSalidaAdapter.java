package com.supermercado.pagos.infrastructure.messaging.outbox;

import com.supermercado.pagos.application.port.EventoSalidaPort;
import com.supermercado.pagos.infrastructure.messaging.config.RabbitMQConfig;
import com.supermercado.pagos.infrastructure.messaging.dto.EventEnvelope;
import com.supermercado.pagos.infrastructure.messaging.dto.PagoAprobadoPayload;
import com.supermercado.pagos.infrastructure.messaging.dto.PagoRechazadoPayload;
import com.supermercado.pagos.infrastructure.persistence.entity.EventoSalidaEntity;
import com.supermercado.pagos.infrastructure.persistence.repository.SpringDataEventoSalidaRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Guarda el evento (envelope completo en JSON) en eventos_salida, dentro de la transaccion del
 * caso de uso que llama. El eventId es determinista (tipo + pagoId): registrar dos veces el mismo
 * evento colisiona en la clave primaria en vez de duplicarlo.
 */
@Component
@RequiredArgsConstructor
public class EventoSalidaAdapter implements EventoSalidaPort {

    private static final String PRODUCER = "pagos-service";

    private final SpringDataEventoSalidaRepository springDataEventoSalidaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void registrar(PagoAprobado evento, UUID correlationId) {
        PagoAprobadoPayload payload = new PagoAprobadoPayload(evento.pagoId(), evento.pedidoId(), evento.carritoId(),
                evento.usuarioId(), evento.monto(), evento.moneda(),
                evento.items().stream().map(i -> new PagoAprobadoPayload.ItemDto(i.productoId(), i.cantidad())).toList());
        guardar(RabbitMQConfig.ROUTING_KEY_PAGO_APROBADO, evento.pagoId(), payload, correlationId);
    }

    @Override
    public void registrar(PagoRechazado evento, UUID correlationId) {
        PagoRechazadoPayload payload = new PagoRechazadoPayload(evento.pagoId(), evento.pedidoId(), evento.usuarioId(),
                evento.monto(), evento.moneda(), evento.motivo());
        guardar(RabbitMQConfig.ROUTING_KEY_PAGO_RECHAZADO, evento.pagoId(), payload, correlationId);
    }

    private void guardar(String tipo, UUID pagoId, Object payload, UUID correlationId) {
        UUID eventId = UUID.nameUUIDFromBytes((tipo + ":" + pagoId).getBytes(StandardCharsets.UTF_8));
        Instant ahora = Instant.now();
        EventEnvelope envelope = new EventEnvelope(eventId, tipo, 1, ahora, PRODUCER, correlationId,
                objectMapper.valueToTree(payload));

        EventoSalidaEntity entity = new EventoSalidaEntity();
        entity.setId(eventId);
        entity.setEventType(tipo);
        entity.setRoutingKey(tipo);
        entity.setCorrelationId(correlationId);
        entity.setPayload(objectMapper.writeValueAsString(envelope));
        entity.setCreadoEn(ahora);
        entity.setIntentos(0);
        springDataEventoSalidaRepository.saveAndFlush(entity);
    }
}
