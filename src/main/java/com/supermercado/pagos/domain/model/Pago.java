package com.supermercado.pagos.domain.model;

import com.supermercado.pagos.domain.exception.PagoInvalidoException;
import com.supermercado.pagos.domain.model.PoliticaDePago.Decision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public class Pago {

    private static final int MAX_USUARIO_ID = 100;
    private static final Pattern MONEDA = Pattern.compile("^[A-Z]{3}$");

    private final PagoId id;
    private final UUID pedidoId;
    private final String usuarioId;
    private final BigDecimal monto;
    private final String moneda;
    private final EstadoPago estado;
    private final MotivoRechazo motivoRechazo;
    private final Instant creadoEn;

    private Pago(PagoId id, UUID pedidoId, String usuarioId, BigDecimal monto, String moneda,
                 EstadoPago estado, MotivoRechazo motivoRechazo, Instant creadoEn) {
        this.id = id;
        this.pedidoId = pedidoId;
        this.usuarioId = usuarioId;
        this.monto = monto;
        this.moneda = moneda;
        this.estado = estado;
        this.motivoRechazo = motivoRechazo;
        this.creadoEn = creadoEn;
    }

    public static Pago crear(UUID pedidoId, String usuarioId, BigDecimal monto, String moneda, Decision decision) {
        if (pedidoId == null) throw new PagoInvalidoException("El id del pedido no puede ser nulo");
        if (usuarioId == null || usuarioId.isBlank() || usuarioId.length() > MAX_USUARIO_ID) {
            throw new PagoInvalidoException("El id de usuario es obligatorio y de hasta " + MAX_USUARIO_ID + " caracteres");
        }
        if (monto == null || monto.signum() <= 0) throw new PagoInvalidoException("El monto debe ser mayor a cero");
        if (moneda == null || !MONEDA.matcher(moneda).matches()) {
            throw new PagoInvalidoException("La moneda debe ser un codigo de 3 letras mayusculas");
        }
        if (decision == null) throw new PagoInvalidoException("La decision de pago no puede ser nula");
        if ((decision.estado() == EstadoPago.RECHAZADO) != (decision.motivo() != null)) {
            throw new PagoInvalidoException("Un pago rechazado exige motivo y uno aprobado no puede tenerlo");
        }
        return new Pago(PagoId.nuevo(), pedidoId, usuarioId, monto, moneda, decision.estado(), decision.motivo(), Instant.now());
    }

    public static Pago reconstruir(PagoId id, UUID pedidoId, String usuarioId, BigDecimal monto, String moneda,
                                   EstadoPago estado, MotivoRechazo motivoRechazo, Instant creadoEn) {
        return new Pago(Objects.requireNonNull(id, "El id no puede ser nulo"), pedidoId, usuarioId, monto, moneda,
                Objects.requireNonNull(estado, "El estado no puede ser nulo"), motivoRechazo,
                Objects.requireNonNull(creadoEn, "creadoEn no puede ser nulo"));
    }

    public PagoId getId() { return id; }
    public UUID getPedidoId() { return pedidoId; }
    public String getUsuarioId() { return usuarioId; }
    public BigDecimal getMonto() { return monto; }
    public String getMoneda() { return moneda; }
    public EstadoPago getEstado() { return estado; }
    public MotivoRechazo getMotivoRechazo() { return motivoRechazo; }
    public Instant getCreadoEn() { return creadoEn; }
}
