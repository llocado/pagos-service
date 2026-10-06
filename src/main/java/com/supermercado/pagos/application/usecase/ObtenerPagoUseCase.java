package com.supermercado.pagos.application.usecase;

import com.supermercado.pagos.application.port.PagoRepositoryPort;
import com.supermercado.pagos.domain.exception.PagoNoEncontradoException;
import com.supermercado.pagos.domain.model.Pago;
import com.supermercado.pagos.domain.model.PagoId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// No es "final": @Transactional necesita que Spring genere un proxy CGLIB.
@Service
@RequiredArgsConstructor
public class ObtenerPagoUseCase {

    private final PagoRepositoryPort pagoRepositoryPort;

    /**
     * Se exige usuarioId ademas del id de pago: si el pago existe pero es de otro usuario se responde
     * igual que "no existe", para no revelar que el id es valido.
     */
    @Transactional(readOnly = true)
    public Pago execute(PagoId id, String usuarioId) {
        Pago pago = pagoRepositoryPort.buscarPorId(id)
                .orElseThrow(() -> new PagoNoEncontradoException("Pago no encontrado: " + id));
        if (!pago.getUsuarioId().equals(usuarioId)) {
            throw new PagoNoEncontradoException("Pago no encontrado: " + id);
        }
        return pago;
    }
}
