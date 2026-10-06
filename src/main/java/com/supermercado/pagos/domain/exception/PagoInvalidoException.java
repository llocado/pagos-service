package com.supermercado.pagos.domain.exception;

/** Regla de dominio violada al crear un pago: el dato es irrecuperable, reintentar no lo arregla. */
public class PagoInvalidoException extends RuntimeException {

    public PagoInvalidoException(String mensaje) {
        super(mensaje);
    }
}
