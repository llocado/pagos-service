package com.supermercado.pagos.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.supermercado.pagos.application.port.PagoRepositoryPort;
import com.supermercado.pagos.domain.exception.PagoNoEncontradoException;
import com.supermercado.pagos.domain.model.Pago;
import com.supermercado.pagos.domain.model.PagoId;
import com.supermercado.pagos.domain.model.PoliticaDePago.Decision;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LecturaDePagosUseCaseTest {

    @Mock
    private PagoRepositoryPort pagoRepositoryPort;

    private Pago pagoDe(String usuarioId) {
        return Pago.crear(UUID.randomUUID(), usuarioId, new BigDecimal("3000"), "CLP", Decision.aprobado());
    }

    @Test
    void listar_DeberiaRetornarLosPagosDelUsuario() {
        Pago pago = pagoDe("usuario-1");
        when(pagoRepositoryPort.buscarPorUsuarioId("usuario-1")).thenReturn(List.of(pago));

        assertThat(new ListarPagosUseCase(pagoRepositoryPort).execute("usuario-1")).containsExactly(pago);
    }

    @Test
    void obtener_DeberiaRetornarElPago_CuandoEsDelUsuario() {
        Pago pago = pagoDe("usuario-1");
        when(pagoRepositoryPort.buscarPorId(pago.getId())).thenReturn(Optional.of(pago));

        assertThat(new ObtenerPagoUseCase(pagoRepositoryPort).execute(pago.getId(), "usuario-1")).isSameAs(pago);
    }

    @Test
    void obtener_DeberiaResponderComoSiNoExistiera_CuandoElPagoEsDeOtroUsuario() {
        Pago pago = pagoDe("usuario-1");
        when(pagoRepositoryPort.buscarPorId(pago.getId())).thenReturn(Optional.of(pago));

        assertThatThrownBy(() -> new ObtenerPagoUseCase(pagoRepositoryPort).execute(pago.getId(), "otro-usuario"))
                .isInstanceOf(PagoNoEncontradoException.class);
    }

    @Test
    void obtener_DeberiaFallar_CuandoNoExiste() {
        PagoId id = PagoId.nuevo();
        when(pagoRepositoryPort.buscarPorId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new ObtenerPagoUseCase(pagoRepositoryPort).execute(id, "usuario-1"))
                .isInstanceOf(PagoNoEncontradoException.class);
    }
}
