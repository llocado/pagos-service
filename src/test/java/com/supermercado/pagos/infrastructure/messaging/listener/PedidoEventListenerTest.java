package com.supermercado.pagos.infrastructure.messaging.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.supermercado.pagos.application.usecase.ProcesarPagoDePedidoUseCase;
import com.supermercado.pagos.application.usecase.ProcesarPagoDePedidoUseCase.Comando;
import com.supermercado.pagos.domain.exception.PagoInvalidoException;
import com.supermercado.pagos.infrastructure.messaging.dto.EventEnvelope;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class PedidoEventListenerTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID PEDIDO_ID = UUID.randomUUID();
    private static final UUID CARRITO_ID = UUID.randomUUID();
    private static final String ITEM = item(1500, 2);

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private ProcesarPagoDePedidoUseCase procesarPago;

    private PedidoEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new PedidoEventListener(procesarPago, objectMapper);
    }

    @Test
    void deberiaDelegarEnElCasoDeUso_CuandoElPayloadEsValido() {
        listener.escucharPedidoCreado(envelope(payload("\"u1\"", "[" + ITEM + "]", "3000")));

        ArgumentCaptor<Comando> comando = ArgumentCaptor.forClass(Comando.class);
        verify(procesarPago).execute(comando.capture());
        assertThat(comando.getValue().eventId()).isEqualTo(EVENT_ID);
        assertThat(comando.getValue().pedidoId()).isEqualTo(PEDIDO_ID);
        assertThat(comando.getValue().moneda()).isEqualTo("CLP");
        assertThat(comando.getValue().total()).isEqualByComparingTo("3000");
        assertThat(comando.getValue().items()).hasSize(1);
        assertThat(comando.getValue().correlationId()).isEqualTo(EVENT_ID);
    }

    @Test
    void deberiaPropagarElCorrelationIdDelEnvelope_CuandoViene() {
        UUID correlacion = UUID.randomUUID();
        EventEnvelope conCorrelacion = new EventEnvelope(EVENT_ID, "pedido.creado", 1, Instant.now(), "pedidos-service",
                correlacion, payload("\"u1\"", "[" + ITEM + "]", "3000"));

        listener.escucharPedidoCreado(conCorrelacion);

        ArgumentCaptor<Comando> comando = ArgumentCaptor.forClass(Comando.class);
        verify(procesarPago).execute(comando.capture());
        assertThat(comando.getValue().correlationId()).isEqualTo(correlacion);
    }

    @Test
    void deberiaRechazar_CuandoElPayloadEstaVacio() {
        assertRechazado(envelope(objectMapper.readTree("{}")));
    }

    @Test
    void deberiaRechazar_CuandoFaltaElPedidoOElCarrito() {
        assertRechazado(envelope(objectMapper.readTree("{\"carritoId\":\"" + CARRITO_ID + "\",\"usuarioId\":\"u1\",\"items\":[" + ITEM + "],\"total\":3000}")));
        assertRechazado(envelope(objectMapper.readTree("{\"pedidoId\":\"" + PEDIDO_ID + "\",\"usuarioId\":\"u1\",\"items\":[" + ITEM + "],\"total\":3000}")));
    }

    @Test
    void deberiaAceptarUnUsuarioIdDeExactamente100Caracteres_YRechazarUnoDe101() {
        listener.escucharPedidoCreado(envelope(payload("\"" + "u".repeat(100) + "\"", "[" + ITEM + "]", "3000")));
        verify(procesarPago).execute(any());
        clearInvocations(procesarPago);

        assertRechazado(envelope(payload("\"" + "u".repeat(101) + "\"", "[" + ITEM + "]", "3000")));
    }

    @Test
    void deberiaRechazar_CuandoElUsuarioEstaVacioOAusente() {
        assertRechazado(envelope(payload("\" \"", "[" + ITEM + "]", "3000")));
        assertRechazado(envelope(payload("null", "[" + ITEM + "]", "3000")));
    }

    @Test
    void deberiaRechazar_CuandoNoHayItems() {
        assertRechazado(envelope(payload("\"u1\"", "[]", "3000")));
        assertRechazado(envelope(payload("\"u1\"", "null", "3000")));
    }

    @Test
    void deberiaAceptarExactamente500Items_YRechazar501() {
        listener.escucharPedidoCreado(envelope(payload("\"u1\"", itemsRepetidos(PedidoEventListener.MAX_ITEMS), String.valueOf(PedidoEventListener.MAX_ITEMS))));
        verify(procesarPago).execute(any());
        clearInvocations(procesarPago);

        assertRechazado(envelope(payload("\"u1\"", itemsRepetidos(PedidoEventListener.MAX_ITEMS + 1), String.valueOf(PedidoEventListener.MAX_ITEMS + 1))));
    }

    @Test
    void deberiaRechazar_CuandoUnItemEsInvalido() {
        assertRechazado(envelope(payload("\"u1\"", "[" + item(1500, 0) + "]", "0")));
        assertRechazado(envelope(payload("\"u1\"", "[" + item(-1, 1) + "]", "3000")));
        assertRechazado(envelope(payload("\"u1\"", "[" + ITEM.replace("\"CLP\"", "\"clp\"") + "]", "3000")));
        assertRechazado(envelope(payload("\"u1\"", "[" + ITEM.replace("\"CLP\"", "null") + "]", "3000")));
        assertRechazado(envelope(payload("\"u1\"", "[null]", "3000")));
        assertRechazado(envelope(payload("\"u1\"", "[" + ITEM.replaceFirst("\"productoId\":\"[^\"]+\",", "") + "]", "3000")));
    }

    @Test
    void deberiaRechazar_CuandoLosItemsTienenMonedasDistintas() {
        String usd = ITEM.replace("\"CLP\"", "\"USD\"");
        assertRechazado(envelope(payload("\"u1\"", "[" + ITEM + "," + usd + "]", "6000")));
    }

    @Test
    void deberiaRechazar_CuandoElTotalNoCoincideConLaSumaDeLosItems() {
        assertRechazado(envelope(payload("\"u1\"", "[" + ITEM + "]", "2999")));
        assertRechazado(envelope(payload("\"u1\"", "[" + ITEM + "]", "3001")));
    }

    @Test
    void deberiaRechazar_CuandoElTotalEsCeroNuloONegativo() {
        assertRechazado(envelope(payload("\"u1\"", "[" + item(0, 1) + "]", "0")));
        assertRechazado(envelope(payload("\"u1\"", "[" + ITEM + "]", "null")));
        assertRechazado(envelope(payload("\"u1\"", "[" + ITEM + "]", "-3000")));
    }

    @Test
    void deberiaRechazar_CuandoUnCampoTieneElTipoIncorrecto() {
        assertRechazado(envelope(objectMapper.readTree("{\"pedidoId\":\"no-es-uuid\",\"usuarioId\":\"u1\",\"items\":[],\"total\":1}")));
    }

    @Test
    void deberiaRechazar_CuandoElEnvelopeNoTieneEventIdONiPayload() {
        EventEnvelope sinId = new EventEnvelope(null, "pedido.creado", 1, Instant.now(), "pedidos-service", null,
                payload("\"u1\"", "[" + ITEM + "]", "3000"));
        EventEnvelope sinPayload = new EventEnvelope(EVENT_ID, "pedido.creado", 1, Instant.now(), "pedidos-service", null, null);

        assertThatThrownBy(() -> listener.escucharPedidoCreado(sinId)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.escucharPedidoCreado(sinPayload)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.escucharPedidoCreado(null)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(procesarPago, never()).execute(any());
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoElCasoDeUsoViolaUnaReglaDeDominio() {
        doThrow(new PagoInvalidoException("moneda invalida")).when(procesarPago).execute(any());

        assertThatThrownBy(() -> listener.escucharPedidoCreado(envelope(payload("\"u1\"", "[" + ITEM + "]", "3000"))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void deberiaPropagarElErrorTalCual_CuandoElFalloEsTransitorio() {
        IllegalStateException transitorio = new IllegalStateException("base de datos no disponible");
        doThrow(transitorio).when(procesarPago).execute(any());

        assertThatThrownBy(() -> listener.escucharPedidoCreado(envelope(payload("\"u1\"", "[" + ITEM + "]", "3000"))))
                .isSameAs(transitorio);
    }

    @Test
    void deberiaDescartarElDuplicadoConcurrente_CuandoElChoqueDeUnicidadEsPorUnEventoYaProcesado() {
        doThrow(new DataIntegrityViolationException("uq_pagos_pedido_id")).when(procesarPago).execute(any());
        when(procesarPago.yaProcesado(EVENT_ID, PEDIDO_ID)).thenReturn(true);

        assertThatCode(() -> listener.escucharPedidoCreado(envelope(payload("\"u1\"", "[" + ITEM + "]", "3000"))))
                .doesNotThrowAnyException();
    }

    @Test
    void deberiaPropagarElErrorDeIntegridad_CuandoNoEraUnDuplicado() {
        DataIntegrityViolationException error = new DataIntegrityViolationException("valor demasiado largo");
        doThrow(error).when(procesarPago).execute(any());
        when(procesarPago.yaProcesado(EVENT_ID, PEDIDO_ID)).thenReturn(false);

        assertThatThrownBy(() -> listener.escucharPedidoCreado(envelope(payload("\"u1\"", "[" + ITEM + "]", "3000"))))
                .isSameAs(error);
    }

    @Test
    void deberiaLimpiarElMdc_AlTerminar() {
        listener.escucharPedidoCreado(envelope(payload("\"u1\"", "[" + ITEM + "]", "3000")));

        assertThat(org.slf4j.MDC.get("correlationId")).isNull();
    }

    private void assertRechazado(EventEnvelope envelope) {
        assertThatThrownBy(() -> listener.escucharPedidoCreado(envelope)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(procesarPago, never()).execute(any());
    }

    private static String item(int precio, int cantidad) {
        return "{\"productoId\":\"" + UUID.randomUUID() + "\",\"nombre\":\"Manzana\",\"precioUnitario\":" + precio
                + ",\"moneda\":\"CLP\",\"cantidad\":" + cantidad + "}";
    }

    private static String itemsRepetidos(int n) {
        return "[" + String.join(",", Collections.nCopies(n, item(1, 1))) + "]";
    }

    private JsonNode payload(String usuarioId, String items, String total) {
        return objectMapper.readTree("{\"pedidoId\":\"" + PEDIDO_ID + "\",\"carritoId\":\"" + CARRITO_ID
                + "\",\"usuarioId\":" + usuarioId + ",\"items\":" + items + ",\"total\":" + total + "}");
    }

    private EventEnvelope envelope(JsonNode payload) {
        return new EventEnvelope(EVENT_ID, "pedido.creado", 1, Instant.now(), "pedidos-service", null, payload);
    }
}
