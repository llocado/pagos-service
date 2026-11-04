package com.supermercado.pagos.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.supermercado.pagos.application.port.EventoProcesadoPort;
import com.supermercado.pagos.application.port.EventoSalidaPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurgarEventosAntiguosUseCaseTest {

    private static final Instant AHORA = Instant.parse("2026-11-03T12:00:00Z");
    private static final int LOTE = 1000;

    @Mock
    private EventoProcesadoPort eventoProcesadoPort;
    @Mock
    private EventoSalidaPort eventoSalidaPort;

    private PurgarEventosAntiguosUseCase useCase;

    @BeforeEach
    void preparar() {
        useCase = new PurgarEventosAntiguosUseCase(eventoProcesadoPort, eventoSalidaPort, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @Test
    void execute_DeberiaCalcularCadaLimiteConSuPropioPlazo() {
        useCase.execute(Duration.ofDays(30), Duration.ofDays(7), LOTE);

        verify(eventoProcesadoPort).eliminarProcesadosAnterioresA(Instant.parse("2026-10-04T12:00:00Z"), LOTE);
        verify(eventoSalidaPort).eliminarPublicadosAnterioresA(Instant.parse("2026-10-27T12:00:00Z"), LOTE);
    }

    @Test
    void execute_NoDeberiaRepetirElLote_CuandoNoHayNadaQueBorrar() {
        var resultado = useCase.execute(Duration.ofDays(30), Duration.ofDays(7), LOTE);

        assertThat(resultado).isEqualTo(new PurgarEventosAntiguosUseCase.Resultado(0, 0));
        verify(eventoProcesadoPort, times(1)).eliminarProcesadosAnterioresA(any(), anyInt());
        verify(eventoSalidaPort, times(1)).eliminarPublicadosAnterioresA(any(), anyInt());
    }

    @Test
    void execute_DeberiaSeguirBorrandoMientrasElLoteVengaCompleto_YPararAlVerUnoIncompleto() {
        when(eventoProcesadoPort.eliminarProcesadosAnterioresA(any(), anyInt())).thenReturn(LOTE, LOTE, 250);

        var resultado = useCase.execute(Duration.ofDays(30), Duration.ofDays(7), LOTE);

        assertThat(resultado.procesados()).isEqualTo(2250);
        verify(eventoProcesadoPort, times(3)).eliminarProcesadosAnterioresA(any(), anyInt());
    }

    @Test
    void execute_UnLoteExactamenteCompleto_ObligaAUnaConsultaMas() {
        // Si borro justo "lote" filas no se sabe si quedan mas: hay que preguntar otra vez (la segunda devuelve 0).
        when(eventoSalidaPort.eliminarPublicadosAnterioresA(any(), anyInt())).thenReturn(LOTE, 0);

        var resultado = useCase.execute(Duration.ofDays(30), Duration.ofDays(7), LOTE);

        assertThat(resultado.salida()).isEqualTo(LOTE);
        verify(eventoSalidaPort, times(2)).eliminarPublicadosAnterioresA(any(), anyInt());
    }

    @Test
    void execute_LasDosTablasSonIndependientes() {
        when(eventoProcesadoPort.eliminarProcesadosAnterioresA(any(), anyInt())).thenReturn(5);
        when(eventoSalidaPort.eliminarPublicadosAnterioresA(any(), anyInt())).thenReturn(3);

        var resultado = useCase.execute(Duration.ofDays(1), Duration.ofDays(1), LOTE);

        assertThat(resultado).isEqualTo(new PurgarEventosAntiguosUseCase.Resultado(5, 3));
    }
}
