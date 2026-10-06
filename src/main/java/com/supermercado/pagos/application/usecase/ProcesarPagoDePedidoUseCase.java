package com.supermercado.pagos.application.usecase;

import com.supermercado.pagos.application.port.EventoProcesadoPort;
import com.supermercado.pagos.application.port.EventoSalidaPort;
import com.supermercado.pagos.application.port.EventoSalidaPort.ItemComprado;
import com.supermercado.pagos.application.port.EventoSalidaPort.PagoAprobado;
import com.supermercado.pagos.application.port.EventoSalidaPort.PagoRechazado;
import com.supermercado.pagos.application.port.PagoRepositoryPort;
import com.supermercado.pagos.domain.model.EstadoPago;
import com.supermercado.pagos.domain.model.Pago;
import com.supermercado.pagos.domain.model.PoliticaDePago;
import com.supermercado.pagos.domain.model.PoliticaDePago.Decision;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// No es "final": @Transactional necesita que Spring genere un proxy CGLIB.
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcesarPagoDePedidoUseCase {

    public record Comando(UUID eventId, UUID correlationId, UUID pedidoId, UUID carritoId, String usuarioId,
                          BigDecimal total, String moneda, List<ItemComprado> items) {
    }

    private final PagoRepositoryPort pagoRepositoryPort;
    private final EventoProcesadoPort eventoProcesadoPort;
    private final EventoSalidaPort eventoSalidaPort;
    private final PoliticaDePago politicaDePago;

    /**
     * Idempotente por eventId Y por pedido: RabbitMQ entrega at-least-once y un pedido solo se paga
     * una vez. Todo ocurre en una transaccion: pago, evento de salida (outbox) y marca de evento
     * procesado se confirman juntos o no se confirma nada.
     */
    @Transactional
    public void execute(Comando comando) {
        if (eventoProcesadoPort.yaFueProcesado(comando.eventId())) {
            log.info("Evento ya procesado, se descarta el duplicado eventId={}", comando.eventId());
            return;
        }
        if (pagoRepositoryPort.existePorPedidoId(comando.pedidoId())) {
            eventoProcesadoPort.marcarComoProcesado(comando.eventId());
            log.info("El pedido ya tiene un pago, se descarta el evento eventId={} pedidoId={}",
                    comando.eventId(), comando.pedidoId());
            return;
        }

        Decision decision = politicaDePago.evaluar(comando.total());
        Pago pago = pagoRepositoryPort.guardar(
                Pago.crear(comando.pedidoId(), comando.usuarioId(), comando.total(), comando.moneda(), decision));

        if (decision.estado() == EstadoPago.APROBADO) {
            eventoSalidaPort.registrar(new PagoAprobado(pago.getId().getValor(), pago.getPedidoId(), comando.carritoId(),
                    pago.getUsuarioId(), pago.getMonto(), pago.getMoneda(), comando.items()), comando.correlationId());
        } else {
            eventoSalidaPort.registrar(new PagoRechazado(pago.getId().getValor(), pago.getPedidoId(), pago.getUsuarioId(),
                    pago.getMonto(), pago.getMoneda(), pago.getMotivoRechazo().name()), comando.correlationId());
        }
        eventoProcesadoPort.marcarComoProcesado(comando.eventId());

        log.info("Pago {} pagoId={} pedidoId={} usuarioId={} monto={} {}", pago.getEstado(), pago.getId().getValor(),
                pago.getPedidoId(), pago.getUsuarioId(), pago.getMonto(), pago.getMoneda());
    }

    /** Para el listener: tras un choque de unicidad concurrente, distingue "duplicado" de un error real. */
    @Transactional(readOnly = true)
    public boolean yaProcesado(UUID eventId, UUID pedidoId) {
        return eventoProcesadoPort.yaFueProcesado(eventId) || pagoRepositoryPort.existePorPedidoId(pedidoId);
    }
}
