package com.supermercado.pagos.domain.model;

import com.supermercado.pagos.domain.exception.PagoInvalidoException;
import java.math.BigDecimal;

/**
 * Pago simulado: no hay banco real. La decision es determinista a proposito (se puede probar el
 * rechazo en tests, en Bruno y en las pruebas de fallo): se rechaza si el monto supera el umbral.
 */
public final class PoliticaDePago {

    public record Decision(EstadoPago estado, MotivoRechazo motivo) {

        public static Decision aprobado() {
            return new Decision(EstadoPago.APROBADO, null);
        }

        public static Decision rechazado(MotivoRechazo motivo) {
            return new Decision(EstadoPago.RECHAZADO, motivo);
        }
    }

    private final BigDecimal umbralRechazo;

    public PoliticaDePago(BigDecimal umbralRechazo) {
        if (umbralRechazo == null || umbralRechazo.signum() <= 0) {
            throw new PagoInvalidoException("El umbral de rechazo debe ser mayor a cero");
        }
        this.umbralRechazo = umbralRechazo;
    }

    /** Un monto exactamente igual al umbral se aprueba: solo lo que lo supera se rechaza. */
    public Decision evaluar(BigDecimal monto) {
        if (monto == null) {
            throw new PagoInvalidoException("El monto no puede ser nulo");
        }
        return monto.compareTo(umbralRechazo) > 0
                ? Decision.rechazado(MotivoRechazo.MONTO_EXCEDE_LIMITE)
                : Decision.aprobado();
    }
}
