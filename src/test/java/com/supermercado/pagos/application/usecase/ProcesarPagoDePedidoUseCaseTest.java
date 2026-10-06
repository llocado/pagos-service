package com.supermercado.pagos.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.supermercado.pagos.application.port.EventoProcesadoPort;
import com.supermercado.pagos.application.port.EventoSalidaPort;
import com.supermercado.pagos.application.port.EventoSalidaPort.ItemComprado;
import com.supermercado.pagos.application.port.EventoSalidaPort.PagoAprobado;
import com.supermercado.pagos.application.port.EventoSalidaPort.PagoRechazado;
import com.supermercado.pagos.application.port.PagoRepositoryPort;
import com.supermercado.pagos.application.usecase.ProcesarPagoDePedidoUseCase.Comando;
import com.supermercado.pagos.domain.exception.PagoInvalidoException;
import com.supermercado.pagos.domain.model.EstadoPago;
import com.supermercado.pagos.domain.model.Pago;
import com.supermercado.pagos.domain.model.PoliticaDePago;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcesarPagoDePedidoUseCaseTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final UUID PEDIDO_ID = UUID.randomUUID();
    private static final UUID CARRITO_ID = UUID.randomUUID();
    private static final UUID PRODUCTO_ID = UUID.randomUUID();

    @Mock
    private PagoRepositoryPort pagoRepositoryPort;

    @Mock
    private EventoProcesadoPort eventoProcesadoPort;

    @Mock
    private EventoSalidaPort eventoSalidaPort;

    private ProcesarPagoDePedidoUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ProcesarPagoDePedidoUseCase(pagoRepositoryPort, eventoProcesadoPort, eventoSalidaPort,
                new PoliticaDePago(new BigDecimal("100000")));
    }

    @Test
    void execute_DeberiaAprobarGuardarYRegistrarElEvento_CuandoElMontoEstaBajoElUmbral() {
        when(pagoRepositoryPort.guardar(any(Pago.class))).thenAnswer(i -> i.getArgument(0));

        useCase.execute(comando(new BigDecimal("3000"), "CLP"));

        ArgumentCaptor<Pago> pago = ArgumentCaptor.forClass(Pago.class);
        verify(pagoRepositoryPort).guardar(pago.capture());
        assertThat(pago.getValue().getEstado()).isEqualTo(EstadoPago.APROBADO);

        ArgumentCaptor<PagoAprobado> evento = ArgumentCaptor.forClass(PagoAprobado.class);
        verify(eventoSalidaPort).registrar(evento.capture(), org.mockito.ArgumentMatchers.eq(CORRELATION_ID));
        assertThat(evento.getValue().pagoId()).isEqualTo(pago.getValue().getId().getValor());
        assertThat(evento.getValue().pedidoId()).isEqualTo(PEDIDO_ID);
        assertThat(evento.getValue().carritoId()).isEqualTo(CARRITO_ID);
        assertThat(evento.getValue().items()).containsExactly(new ItemComprado(PRODUCTO_ID, 2));
        verify(eventoSalidaPort, never()).registrar(any(PagoRechazado.class), any());
        verify(eventoProcesadoPort).marcarComoProcesado(EVENT_ID);
    }

    @Test
    void execute_DeberiaRechazarYRegistrarElEventoDeRechazo_CuandoElMontoSuperaElUmbral() {
        when(pagoRepositoryPort.guardar(any(Pago.class))).thenAnswer(i -> i.getArgument(0));

        useCase.execute(comando(new BigDecimal("150000"), "CLP"));

        ArgumentCaptor<PagoRechazado> evento = ArgumentCaptor.forClass(PagoRechazado.class);
        verify(eventoSalidaPort).registrar(evento.capture(), org.mockito.ArgumentMatchers.eq(CORRELATION_ID));
        assertThat(evento.getValue().motivo()).isEqualTo("MONTO_EXCEDE_LIMITE");
        assertThat(evento.getValue().monto()).isEqualByComparingTo("150000");
        verify(eventoSalidaPort, never()).registrar(any(PagoAprobado.class), any());
        verify(eventoProcesadoPort).marcarComoProcesado(EVENT_ID);
    }

    @Test
    void execute_NoDeberiaHacerNada_CuandoElEventoYaFueProcesado() {
        when(eventoProcesadoPort.yaFueProcesado(EVENT_ID)).thenReturn(true);

        useCase.execute(comando(new BigDecimal("3000"), "CLP"));

        verify(pagoRepositoryPort, never()).guardar(any());
        verify(eventoSalidaPort, never()).registrar(any(PagoAprobado.class), any());
        verify(eventoSalidaPort, never()).registrar(any(PagoRechazado.class), any());
        verify(eventoProcesadoPort, never()).marcarComoProcesado(any());
    }

    @Test
    void execute_DeberiaMarcarElEventoYNoPagarDeNuevo_CuandoElPedidoYaTieneUnPago() {
        when(pagoRepositoryPort.existePorPedidoId(PEDIDO_ID)).thenReturn(true);

        useCase.execute(comando(new BigDecimal("3000"), "CLP"));

        verify(eventoProcesadoPort).marcarComoProcesado(EVENT_ID);
        verify(pagoRepositoryPort, never()).guardar(any());
        verify(eventoSalidaPort, never()).registrar(any(PagoAprobado.class), any());
    }

    @Test
    void execute_DeberiaFallarSinRegistrarNiMarcarNada_CuandoLaMonedaEsInvalida() {
        assertThatThrownBy(() -> useCase.execute(comando(new BigDecimal("3000"), "clp")))
                .isInstanceOf(PagoInvalidoException.class);

        verify(pagoRepositoryPort, never()).guardar(any());
        verify(eventoSalidaPort, never()).registrar(any(PagoAprobado.class), any());
        verify(eventoProcesadoPort, never()).marcarComoProcesado(any());
    }

    @Test
    void yaProcesado_DeberiaSerVerdadero_SiElEventoOElPedidoYaSeProcesaron() {
        assertThat(useCase.yaProcesado(EVENT_ID, PEDIDO_ID)).isFalse();

        when(eventoProcesadoPort.yaFueProcesado(EVENT_ID)).thenReturn(true);
        assertThat(useCase.yaProcesado(EVENT_ID, PEDIDO_ID)).isTrue();
    }

    @Test
    void yaProcesado_DeberiaSerVerdadero_SiElPedidoYaTienePago() {
        when(pagoRepositoryPort.existePorPedidoId(PEDIDO_ID)).thenReturn(true);

        assertThat(useCase.yaProcesado(EVENT_ID, PEDIDO_ID)).isTrue();
    }

    private static Comando comando(BigDecimal total, String moneda) {
        return new Comando(EVENT_ID, CORRELATION_ID, PEDIDO_ID, CARRITO_ID, "usuario-1", total, moneda,
                List.of(new ItemComprado(PRODUCTO_ID, 2)));
    }
}
