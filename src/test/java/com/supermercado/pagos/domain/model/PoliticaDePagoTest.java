package com.supermercado.pagos.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.supermercado.pagos.domain.exception.PagoInvalidoException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PoliticaDePagoTest {

    private final PoliticaDePago politica = new PoliticaDePago(new BigDecimal("100000"));

    @Test
    void evaluar_DeberiaAprobar_CuandoElMontoEstaBajoElUmbral() {
        assertThat(politica.evaluar(new BigDecimal("3000")).estado()).isEqualTo(EstadoPago.APROBADO);
    }

    @Test
    void evaluar_DeberiaAprobar_CuandoElMontoEsExactamenteElUmbral() {
        PoliticaDePago.Decision decision = politica.evaluar(new BigDecimal("100000"));

        assertThat(decision.estado()).isEqualTo(EstadoPago.APROBADO);
        assertThat(decision.motivo()).isNull();
    }

    @Test
    void evaluar_DeberiaRechazarConMotivo_CuandoElMontoSuperaElUmbralPorUnCentavo() {
        PoliticaDePago.Decision decision = politica.evaluar(new BigDecimal("100000.01"));

        assertThat(decision.estado()).isEqualTo(EstadoPago.RECHAZADO);
        assertThat(decision.motivo()).isEqualTo(MotivoRechazo.MONTO_EXCEDE_LIMITE);
    }

    @Test
    void evaluar_DeberiaFallar_CuandoElMontoEsNulo() {
        assertThatThrownBy(() -> politica.evaluar(null)).isInstanceOf(PagoInvalidoException.class);
    }

    @Test
    void constructor_DeberiaFallar_CuandoElUmbralNoEsPositivo() {
        assertThatThrownBy(() -> new PoliticaDePago(null)).isInstanceOf(PagoInvalidoException.class);
        assertThatThrownBy(() -> new PoliticaDePago(BigDecimal.ZERO)).isInstanceOf(PagoInvalidoException.class);
        assertThatThrownBy(() -> new PoliticaDePago(new BigDecimal("-1"))).isInstanceOf(PagoInvalidoException.class);
    }
}
