package com.supermercado.pagos.infrastructure.rest;

import com.supermercado.pagos.application.usecase.ListarPagosUseCase;
import com.supermercado.pagos.application.usecase.ObtenerPagoUseCase;
import com.supermercado.pagos.domain.model.Pago;
import com.supermercado.pagos.domain.model.PagoId;
import com.supermercado.pagos.infrastructure.rest.dto.PagoDtoMapper;
import com.supermercado.pagos.infrastructure.rest.dto.PagoResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Solo lectura: los pagos se crean unicamente al consumir pedido.creado. El id de usuario nunca viene del
 * cliente: sale del claim "sub" del JWT validado por Spring Security.
 */
@RestController
@RequestMapping("/api/pagos")
@RequiredArgsConstructor
public class PagoController {

    private final ListarPagosUseCase listarPagosUseCase;
    private final ObtenerPagoUseCase obtenerPagoUseCase;
    private final PagoDtoMapper pagoDtoMapper;

    @GetMapping
    public ResponseEntity<List<PagoResponse>> listarPagos(@AuthenticationPrincipal Jwt jwt) {
        List<Pago> pagos = listarPagosUseCase.execute(jwt.getSubject());
        return ResponseEntity.ok(pagos.stream().map(pagoDtoMapper::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PagoResponse> obtenerPago(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Pago pago = obtenerPagoUseCase.execute(PagoId.de(id), jwt.getSubject());
        return ResponseEntity.ok(pagoDtoMapper.toResponse(pago));
    }
}
