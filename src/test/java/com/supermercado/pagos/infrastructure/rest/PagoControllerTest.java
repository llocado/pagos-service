package com.supermercado.pagos.infrastructure.rest;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.supermercado.pagos.application.usecase.ListarPagosUseCase;
import com.supermercado.pagos.application.usecase.ObtenerPagoUseCase;
import com.supermercado.pagos.domain.exception.PagoNoEncontradoException;
import com.supermercado.pagos.domain.model.MotivoRechazo;
import com.supermercado.pagos.domain.model.Pago;
import com.supermercado.pagos.domain.model.PoliticaDePago.Decision;
import com.supermercado.pagos.infrastructure.rest.dto.PagoDtoMapper;
import com.supermercado.pagos.infrastructure.security.SecurityConfig;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

/** Slice de presentacion: JwtDecoder se mockea para no contactar a Keycloak; la autenticacion se simula con jwt(). */
@WebMvcTest(PagoController.class)
@Import({PagoDtoMapper.class, SecurityConfig.class})
class PagoControllerTest {

    private static final String USUARIO_ID = "usuario-123";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ListarPagosUseCase listarPagosUseCase;

    @MockitoBean
    private ObtenerPagoUseCase obtenerPagoUseCase;

    @Test
    void listarPagos_DeberiaRetornar200ConLosPagosDelUsuarioDelToken() throws Exception {
        Pago aprobado = Pago.crear(UUID.randomUUID(), USUARIO_ID, new BigDecimal("3000"), "CLP", Decision.aprobado());
        Pago rechazado = Pago.crear(UUID.randomUUID(), USUARIO_ID, new BigDecimal("150000"), "CLP",
                Decision.rechazado(MotivoRechazo.MONTO_EXCEDE_LIMITE));
        when(listarPagosUseCase.execute(USUARIO_ID)).thenReturn(List.of(aprobado, rechazado));

        mockMvc.perform(get("/api/pagos").with(jwtCliente()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].estado").value("APROBADO"))
                .andExpect(jsonPath("$[0].motivoRechazo").doesNotExist())
                .andExpect(jsonPath("$[1].estado").value("RECHAZADO"))
                .andExpect(jsonPath("$[1].motivoRechazo").value("MONTO_EXCEDE_LIMITE"));
    }

    @Test
    void obtenerPago_DeberiaRetornar200_CuandoElPagoEsDelUsuario() throws Exception {
        Pago pago = Pago.crear(UUID.randomUUID(), USUARIO_ID, new BigDecimal("3000"), "CLP", Decision.aprobado());
        when(obtenerPagoUseCase.execute(pago.getId(), USUARIO_ID)).thenReturn(pago);

        mockMvc.perform(get("/api/pagos/{id}", pago.getId().getValor()).with(jwtCliente()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pago.getId().getValor().toString()))
                .andExpect(jsonPath("$.monto").value(3000));
    }

    @Test
    void obtenerPago_DeberiaRetornar404_CuandoNoExisteOEsDeOtroUsuario() throws Exception {
        UUID id = UUID.randomUUID();
        when(obtenerPagoUseCase.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(USUARIO_ID)))
                .thenThrow(new PagoNoEncontradoException("Pago no encontrado: " + id));

        mockMvc.perform(get("/api/pagos/{id}", id).with(jwtCliente()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("Pago no encontrado: " + id));
    }

    @Test
    void obtenerPago_DeberiaRetornar400SinEcoDelValor_CuandoElIdNoEsUnUuid() throws Exception {
        mockMvc.perform(get("/api/pagos/{id}", "no-es-un-uuid<b>").with(jwtCliente()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El valor del parametro 'id' no es valido"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<b>"))));
    }

    @Test
    void deberiaRetornar401_CuandoNoHayToken() throws Exception {
        mockMvc.perform(get("/api/pagos")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/pagos/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void deberiaRetornar403_CuandoElRolNoEsCliente() throws Exception {
        mockMvc.perform(get("/api/pagos").with(jwt().jwt(j -> j.subject(USUARIO_ID)))).andExpect(status().isForbidden());
    }

    @Test
    void listarPagos_DeberiaRetornar503ConMensajeGenerico_CuandoLaBaseNoEstaDisponible() throws Exception {
        when(listarPagosUseCase.execute(USUARIO_ID)).thenThrow(
                new CannotCreateTransactionException("Could not open JPA EntityManager", new RuntimeException("jdbc:postgresql://host:5435 refused")));

        mockMvc.perform(get("/api/pagos").with(jwtCliente()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.mensaje").value("Servicio temporalmente no disponible"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("5435"))));
    }

    @Test
    void listarPagos_DeberiaRetornar503_CuandoLaConexionDelPoolSeRompioPorCaidaDeLaBase() throws Exception {
        when(listarPagosUseCase.execute(USUARIO_ID)).thenThrow(new JpaSystemException(
                new RuntimeException("JDBC exception executing SQL", new SQLException("FATAL: terminating connection", "57P01"))));

        mockMvc.perform(get("/api/pagos").with(jwtCliente()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.mensaje").value("Servicio temporalmente no disponible"));
    }

    @Test
    void listarPagos_DeberiaRetornar503_CuandoElRollbackFallaPorConexionCerrada() throws Exception {
        when(listarPagosUseCase.execute(USUARIO_ID)).thenThrow(new JpaSystemException(
                new RuntimeException("Unable to rollback against JDBC Connection", new SQLException("Connection is closed"))));

        mockMvc.perform(get("/api/pagos").with(jwtCliente()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void listarPagos_DeberiaSeguirSiendo500_CuandoLaJpaSystemExceptionNoEsDeConexion() throws Exception {
        when(listarPagosUseCase.execute(USUARIO_ID)).thenThrow(new JpaSystemException(
                new RuntimeException("JDBC exception executing SQL", new SQLException("columna inexistente", "42703"))));

        mockMvc.perform(get("/api/pagos").with(jwtCliente()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.mensaje").value("Error interno del servidor"));
    }

    private static JwtRequestPostProcessor jwtCliente() {
        return jwt().jwt(j -> j.subject(USUARIO_ID)).authorities(new SimpleGrantedAuthority("ROLE_cliente"));
    }
}
