package com.supermercado.pagos.application.usecase;

import com.supermercado.pagos.application.port.EventoProcesadoPort;
import com.supermercado.pagos.application.port.EventoSalidaPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Retencion de las tablas de eventos, que de otro modo crecerian sin limite:
 * <ul>
 *   <li>{@code eventos_procesados} (idempotencia): se borra lo anotado hace mas de {@code retencionProcesados}. El plazo
 *       debe ser MAYOR que lo que un duplicado puede esperar en una cola (por ejemplo, con un consumidor caido): si se
 *       borra antes, un duplicado tardio se procesaria otra vez.</li>
 *   <li>{@code eventos_salida} (outbox): se borra solo lo YA publicado hace mas de {@code retencionSalida}. Un evento
 *       pendiente jamas se descarta.</li>
 * </ul>
 * Borra en lotes (cada lote es su propia transaccion en el adaptador) hasta que uno viene incompleto.
 */
@Service
@RequiredArgsConstructor
public class PurgarEventosAntiguosUseCase {

    private final EventoProcesadoPort eventoProcesadoPort;
    private final EventoSalidaPort eventoSalidaPort;
    private final Clock clock;

    /** Cuantos registros se borraron de cada tabla. */
    public record Resultado(int procesados, int salida) {
    }

    public Resultado execute(Duration retencionProcesados, Duration retencionSalida, int lote) {
        Instant ahora = clock.instant();
        Instant limiteProcesados = ahora.minus(retencionProcesados);
        Instant limiteSalida = ahora.minus(retencionSalida);
        int procesados = borrarPorLotes(lote, () -> eventoProcesadoPort.eliminarProcesadosAnterioresA(limiteProcesados, lote));
        int salida = borrarPorLotes(lote, () -> eventoSalidaPort.eliminarPublicadosAnterioresA(limiteSalida, lote));
        return new Resultado(procesados, salida);
    }

    private static int borrarPorLotes(int lote, IntSupplier borrarUnLote) {
        int total = 0;
        int borrados;
        do {
            borrados = borrarUnLote.getAsInt();
            total += borrados;
        } while (borrados == lote);
        return total;
    }
}
