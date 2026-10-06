package com.supermercado.pagos.application.usecase;

import com.supermercado.pagos.application.port.PagoRepositoryPort;
import com.supermercado.pagos.domain.model.Pago;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// No es "final": @Transactional necesita que Spring genere un proxy CGLIB.
@Service
@RequiredArgsConstructor
public class ListarPagosUseCase {

    private final PagoRepositoryPort pagoRepositoryPort;

    @Transactional(readOnly = true)
    public List<Pago> execute(String usuarioId) {
        return pagoRepositoryPort.buscarPorUsuarioId(usuarioId);
    }
}
