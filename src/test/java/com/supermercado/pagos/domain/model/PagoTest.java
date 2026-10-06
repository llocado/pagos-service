package com.supermercado.pagos.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.supermercado.pagos.domain.exception.PagoInvalidoException;
import com.supermercado.pagos.domain.model.PoliticaDePago.Decision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PagoTest {

    private static final UUID PEDIDO_ID = UUID.randomUUID();
    private static final BigDecimal MONTO = new BigDecimal("3000");

    @Test
    void crear_DeberiaCrearUnPagoAprobadoSinMotivo() {
        Pago pago = Pago.crear(PEDIDO_ID, "usuario-1", MONTO, "CLP", Decision.aprobado());

        assertThat(pago.getId()).isNotNull();
        assertThat(pago.getPedidoId()).isEqualTo(PEDIDO_ID);
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.APROBADO);
        assertThat(pago.getMotivoRechazo()).isNull();
        assertThat(pago.getCreadoEn()).isNotNull();
    }

    @Test
    void crear_DeberiaCrearUnPagoRechazadoConMotivo() {
        Pago pago = Pago.crear(PEDIDO_ID, "usuario-1", MONTO, "CLP", Decision.rechazado(MotivoRechazo.MONTO_EXCEDE_LIMITE));

        assertThat(pago.getEstado()).isEqualTo(EstadoPago.RECHAZADO);
        assertThat(pago.getMotivoRechazo()).isEqualTo(MotivoRechazo.MONTO_EXCEDE_LIMITE);
    }

    @Test
    void crear_DeberiaAceptarUsuarioIdDeExactamente100Caracteres_YRechazarUnoDe101() {
        assertThat(Pago.crear(PEDIDO_ID, "u".repeat(100), MONTO, "CLP", Decision.aprobado()).getUsuarioId()).hasSize(100);
        assertThatThrownBy(() -> Pago.crear(PEDIDO_ID, "u".repeat(101), MONTO, "CLP", Decision.aprobado()))
                .isInstanceOf(PagoInvalidoException.class);
    }

    @Test
    void crear_DeberiaFallar_CuandoFaltaElPedido() {
        assertThatThrownBy(() -> Pago.crear(null, "usuario-1", MONTO, "CLP", Decision.aprobado()))
                .isInstanceOf(PagoInvalidoException.class);
    }

    @Test
    void crear_DeberiaFallar_CuandoElUsuarioEstaVacio() {
        assertThatThrownBy(() -> Pago.crear(PEDIDO_ID, " ", MONTO, "CLP", Decision.aprobado()))
                .isInstanceOf(PagoInvalidoException.class);
        assertThatThrownBy(() -> Pago.crear(PEDIDO_ID, null, MONTO, "CLP", Decision.aprobado()))
                .isInstanceOf(PagoInvalidoException.class);
    }

    @Test
    void crear_DeberiaFallar_CuandoElMontoNoEsPositivo() {
        assertThatThrownBy(() -> Pago.crear(PEDIDO_ID, "usuario-1", BigDecimal.ZERO, "CLP", Decision.aprobado()))
                .isInstanceOf(PagoInvalidoException.class);
        assertThatThrownBy(() -> Pago.crear(PEDIDO_ID, "usuario-1", null, "CLP", Decision.aprobado()))
                .isInstanceOf(PagoInvalidoException.class);
    }

    @Test
    void crear_DeberiaFallar_CuandoLaMonedaNoEsUnCodigoDeTresLetrasMayusculas() {
        for (String invalida : new String[] {null, "clp", "CL", "CLPX", "C1P"}) {
            assertThatThrownBy(() -> Pago.crear(PEDIDO_ID, "usuario-1", MONTO, invalida, Decision.aprobado()))
                    .isInstanceOf(PagoInvalidoException.class);
        }
    }

    @Test
    void crear_DeberiaFallar_CuandoLaDecisionEsNulaOIncoherente() {
        assertThatThrownBy(() -> Pago.crear(PEDIDO_ID, "usuario-1", MONTO, "CLP", null)).isInstanceOf(PagoInvalidoException.class);
        assertThatThrownBy(() -> Pago.crear(PEDIDO_ID, "usuario-1", MONTO, "CLP", new Decision(EstadoPago.RECHAZADO, null)))
                .isInstanceOf(PagoInvalidoException.class);
        assertThatThrownBy(() -> Pago.crear(PEDIDO_ID, "usuario-1", MONTO, "CLP",
                new Decision(EstadoPago.APROBADO, MotivoRechazo.MONTO_EXCEDE_LIMITE))).isInstanceOf(PagoInvalidoException.class);
    }

    @Test
    void reconstruir_DeberiaRestaurarElEstadoCompleto() {
        PagoId id = PagoId.nuevo();
        Instant creadoEn = Instant.now();

        Pago pago = Pago.reconstruir(id, PEDIDO_ID, "usuario-1", MONTO, "CLP", EstadoPago.RECHAZADO,
                MotivoRechazo.MONTO_EXCEDE_LIMITE, creadoEn);

        assertThat(pago.getId()).isEqualTo(id);
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.RECHAZADO);
        assertThat(pago.getCreadoEn()).isEqualTo(creadoEn);
    }

    @Test
    void pagoId_DeberiaComportarseComoValor() {
        UUID uuid = UUID.randomUUID();

        assertThat(PagoId.de(uuid)).isEqualTo(PagoId.de(uuid)).hasSameHashCodeAs(PagoId.de(uuid));
        assertThat(PagoId.de(uuid)).isNotEqualTo(PagoId.de(UUID.randomUUID()));
        assertThat(PagoId.de(uuid).toString()).isEqualTo(uuid.toString());
        assertThat(PagoId.de(uuid).getValor()).isEqualTo(uuid);
        assertThat(PagoId.nuevo()).isNotEqualTo(PagoId.nuevo());
    }
}
