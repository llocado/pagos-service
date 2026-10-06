package com.supermercado.pagos.domain.model;

import java.util.Objects;
import java.util.UUID;

public final class PagoId {

    private final UUID valor;

    private PagoId(UUID valor) {
        this.valor = Objects.requireNonNull(valor, "El id de pago no puede ser nulo");
    }

    public static PagoId nuevo() {
        return new PagoId(UUID.randomUUID());
    }

    public static PagoId de(UUID valor) {
        return new PagoId(valor);
    }

    public UUID getValor() {
        return valor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return valor.equals(((PagoId) o).valor);
    }

    @Override
    public int hashCode() {
        return valor.hashCode();
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
