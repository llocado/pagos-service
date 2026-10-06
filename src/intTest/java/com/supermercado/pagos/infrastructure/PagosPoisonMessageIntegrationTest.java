package com.supermercado.pagos.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.supermercado.pagos.application.port.PagoRepositoryPort;
import com.supermercado.pagos.application.usecase.ProcesarPagoDePedidoUseCase;
import com.supermercado.pagos.infrastructure.messaging.config.RabbitMQConfig;
import com.supermercado.pagos.infrastructure.messaging.listener.PedidoEventListener;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Mensajes venenosos contra RabbitMQ real: deben terminar en la DLQ, con el numero esperado de intentos,
 * sin bloquear la cola. Las esperas entre reintentos se acortan solo para que el test no tarde.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
})
class PagosPoisonMessageIntegrationTest {

    private static final String DLQ = "pagos.pedido-creado.dlq";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private PagoRepositoryPort pagoRepositoryPort;

    @MockitoSpyBean
    private ProcesarPagoDePedidoUseCase procesarPagoDePedidoUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private ListAppender<ILoggingEvent> logs;
    private Logger loggerListener;

    @BeforeEach
    void limpiarColasYCapturarLogs() {
        rabbitAdmin.purgeQueue(RabbitMQConfig.QUEUE_PEDIDO_CREADO, false);
        rabbitAdmin.purgeQueue(DLQ, false);
        loggerListener = (Logger) LoggerFactory.getLogger(PedidoEventListener.class);
        logs = new ListAppender<>();
        logs.start();
        loggerListener.addAppender(logs);
    }

    @AfterEach
    void soltarLogs() {
        loggerListener.detachAppender(logs);
    }

    @Test
    void payloadInvalido_DeberiaIrALaDlqSinReintentosYSinCrearPago() {
        UUID eventId = UUID.randomUUID();
        String usuario = "usuario-poison-" + UUID.randomUUID();

        publicar("{\"eventId\":\"" + eventId + "\",\"eventType\":\"pedido.creado\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-10-04T12:00:00Z\",\"producer\":\"pedidos-service\","
                + "\"payload\":{\"pedidoId\":\"" + UUID.randomUUID() + "\",\"usuarioId\":\"" + usuario + "\"}}");

        Message enDlq = rabbitTemplate.receive(DLQ, 10_000);
        assertThat(enDlq).as("mensaje en la DLQ").isNotNull();
        assertThat(new String(enDlq.getBody(), StandardCharsets.UTF_8)).contains(eventId.toString());
        verify(procesarPagoDePedidoUseCase, never()).execute(any());
        assertThat(pagoRepositoryPort.buscarPorUsuarioId(usuario)).isEmpty();
        assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().contains("Evento descartado a la DLQ"))
                .as("se descarta una sola vez, sin reintentos").hasSize(1);
        assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.QUEUE_PEDIDO_CREADO).getMessageCount()).isZero();
    }

    @Test
    void jsonRoto_DeberiaIrALaDlq() {
        publicar("esto{no es json");

        assertThat(rabbitTemplate.receive(DLQ, 10_000)).as("mensaje en la DLQ").isNotNull();
        assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.QUEUE_PEDIDO_CREADO).getMessageCount()).isZero();
    }

    @Test
    void falloTransitorioPersistente_DeberiaHacer1IntentoY3ReintentosYLuegoIrALaDlq() throws Exception {
        doThrow(new IllegalStateException("fallo transitorio simulado")).when(procesarPagoDePedidoUseCase).execute(any());

        publicar(PagosIntegrationTest.pedidoCreadoJson(UUID.randomUUID(), null, UUID.randomUUID(), "usuario-transitorio-" + UUID.randomUUID(), "3000"));

        assertThat(rabbitTemplate.receive(DLQ, 10_000)).as("mensaje en la DLQ tras agotar reintentos").isNotNull();
        verify(procesarPagoDePedidoUseCase, times(4)).execute(any());

        Thread.sleep(1500);
        verify(procesarPagoDePedidoUseCase, times(4)).execute(any());
        assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.QUEUE_PEDIDO_CREADO).getMessageCount()).isZero();
    }

    @Test
    void unMensajeVenenoso_NoDeberiaBloquearLaColaParaLosMensajesValidosSiguientes() {
        publicar("{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"pedido.creado\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-10-04T12:00:00Z\",\"producer\":\"pedidos-service\",\"payload\":{}}");
        String usuario = "usuario-valido-" + UUID.randomUUID();
        publicar(PagosIntegrationTest.pedidoCreadoJson(UUID.randomUUID(), null, UUID.randomUUID(), usuario, "3000"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(pagoRepositoryPort.buscarPorUsuarioId(usuario)).hasSize(1));
        assertThat(rabbitTemplate.receive(DLQ, 5_000)).as("el venenoso quedo en la DLQ").isNotNull();
    }

    private void publicar(String cuerpo) {
        Message mensaje = MessageBuilder.withBody(cuerpo.getBytes(StandardCharsets.UTF_8)).setContentType("application/json").build();
        rabbitTemplate.send(RabbitMQConfig.EXCHANGE_PEDIDOS_EVENTS, RabbitMQConfig.ROUTING_KEY_PEDIDO_CREADO, mensaje);
    }
}
