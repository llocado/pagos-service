package com.supermercado.pagos.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.supermercado.pagos.application.port.PagoRepositoryPort;
import com.supermercado.pagos.domain.model.EstadoPago;
import com.supermercado.pagos.domain.model.Pago;
import com.supermercado.pagos.infrastructure.messaging.config.RabbitMQConfig;
import com.supermercado.pagos.infrastructure.messaging.dto.EventEnvelope;
import com.supermercado.pagos.infrastructure.messaging.listener.PedidoEventListener;
import com.supermercado.pagos.infrastructure.persistence.entity.EventoSalidaEntity;
import com.supermercado.pagos.infrastructure.persistence.repository.SpringDataEventoSalidaRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Flujo real de punta a punta dentro del servicio: se publica pedido.creado en RabbitMQ, el listener lo
 * consume, el pago se guarda en PostgreSQL y el evento de salida (outbox) se publica y se lee de una cola
 * de prueba, validado contra el JSON Schema publicado en docs/events/. Solo se mockea JwtDecoder.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "pagos.outbox.intervalo=200ms")
class PagosIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private TopicExchange pagosEventsExchange;

    @Autowired
    private PagoRepositoryPort pagoRepositoryPort;

    @Autowired
    private SpringDataEventoSalidaRepository eventoSalidaRepository;

    @Autowired
    private PedidoEventListener pedidoEventListener;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private String colaDePrueba;

    @AfterEach
    void borrarColaDePrueba() {
        if (colaDePrueba != null) rabbitAdmin.deleteQueue(colaDePrueba);
    }

    @Test
    void pedidoCreado_DeberiaGenerarUnPagoAprobadoYPublicarPagoAprobadoValidoSegunElSchema() throws Exception {
        enlazarColaDePrueba();
        UUID pedidoId = UUID.randomUUID(), correlacion = UUID.randomUUID();
        String usuario = usuarioUnico();

        rabbitTemplate.send(RabbitMQConfig.EXCHANGE_PEDIDOS_EVENTS, RabbitMQConfig.ROUTING_KEY_PEDIDO_CREADO,
                mensaje(pedidoCreadoJson(UUID.randomUUID(), correlacion, pedidoId, usuario, "3000")));

        String json = esperarEventoDe(pedidoId);
        assertThat(validarContraSchema("pago.aprobado", json)).isEmpty();
        JsonNode evento = objectMapper.readTree(json);
        assertThat(evento.get("eventType").asString()).isEqualTo("pago.aprobado");
        assertThat(evento.get("producer").asString()).isEqualTo("pagos-service");
        assertThat(evento.get("correlationId").asString()).isEqualTo(correlacion.toString());
        assertThat(evento.get("payload").get("usuarioId").asString()).isEqualTo(usuario);
        assertThat(evento.get("payload").get("monto").decimalValue()).isEqualByComparingTo("3000");
        assertThat(evento.get("payload").get("items")).hasSize(1);

        Pago pago = pagoRepositoryPort.buscarPorUsuarioId(usuario).get(0);
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.APROBADO);
        assertThat(evento.get("payload").get("pagoId").asString()).isEqualTo(pago.getId().getValor().toString());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(salidaDe(evento).getPublicadoEn()).isNotNull());
    }

    @Test
    void pedidoCreadoSobreElUmbral_DeberiaGenerarUnPagoRechazadoYPublicarPagoRechazadoValido() throws Exception {
        enlazarColaDePrueba();
        UUID pedidoId = UUID.randomUUID();
        String usuario = usuarioUnico();

        rabbitTemplate.send(RabbitMQConfig.EXCHANGE_PEDIDOS_EVENTS, RabbitMQConfig.ROUTING_KEY_PEDIDO_CREADO,
                mensaje(pedidoCreadoJson(UUID.randomUUID(), null, pedidoId, usuario, "150000")));

        String json = esperarEventoDe(pedidoId);
        assertThat(validarContraSchema("pago.rechazado", json)).isEmpty();
        JsonNode evento = objectMapper.readTree(json);
        assertThat(evento.get("eventType").asString()).isEqualTo("pago.rechazado");
        assertThat(evento.get("payload").get("motivo").asString()).isEqualTo("MONTO_EXCEDE_LIMITE");
        assertThat(pagoRepositoryPort.buscarPorUsuarioId(usuario).get(0).getEstado()).isEqualTo(EstadoPago.RECHAZADO);
    }

    @Test
    void elMismoEventoEntregadoDosVeces_DeberiaGenerarUnSoloPagoYUnSoloEvento() throws Exception {
        enlazarColaDePrueba();
        UUID pedidoId = UUID.randomUUID(), eventId = UUID.randomUUID();
        String usuario = usuarioUnico();
        String cuerpo = pedidoCreadoJson(eventId, null, pedidoId, usuario, "3000");

        rabbitTemplate.send(RabbitMQConfig.EXCHANGE_PEDIDOS_EVENTS, RabbitMQConfig.ROUTING_KEY_PEDIDO_CREADO, mensaje(cuerpo));
        rabbitTemplate.send(RabbitMQConfig.EXCHANGE_PEDIDOS_EVENTS, RabbitMQConfig.ROUTING_KEY_PEDIDO_CREADO, mensaje(cuerpo));

        esperarEventoDe(pedidoId);
        Thread.sleep(1500);
        assertThat(pagoRepositoryPort.buscarPorUsuarioId(usuario)).hasSize(1);
        assertThat(eventosRestantesDe(pedidoId)).as("segundo evento de salida").isEmpty();
    }

    @Test
    void dosEventosDistintosParaElMismoPedido_DeberianGenerarUnSoloPago() throws Exception {
        enlazarColaDePrueba();
        UUID pedidoId = UUID.randomUUID();
        String usuario = usuarioUnico();

        rabbitTemplate.send(RabbitMQConfig.EXCHANGE_PEDIDOS_EVENTS, RabbitMQConfig.ROUTING_KEY_PEDIDO_CREADO,
                mensaje(pedidoCreadoJson(UUID.randomUUID(), null, pedidoId, usuario, "3000")));
        rabbitTemplate.send(RabbitMQConfig.EXCHANGE_PEDIDOS_EVENTS, RabbitMQConfig.ROUTING_KEY_PEDIDO_CREADO,
                mensaje(pedidoCreadoJson(UUID.randomUUID(), null, pedidoId, usuario, "3000")));

        esperarEventoDe(pedidoId);
        Thread.sleep(1500);
        assertThat(pagoRepositoryPort.buscarPorUsuarioId(usuario)).hasSize(1);
    }

    @Test
    void ochoEntregasSimultaneasDelMismoEvento_DeberianGenerarUnSoloPagoSinErrores() throws Exception {
        enlazarColaDePrueba();
        UUID pedidoId = UUID.randomUUID();
        String usuario = usuarioUnico();
        EventEnvelope envelope = objectMapper.readValue(pedidoCreadoJson(UUID.randomUUID(), null, pedidoId, usuario, "3000"),
                EventEnvelope.class);

        int hilos = 8;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Throwable> errores = new CopyOnWriteArrayList<>();
        List<java.util.concurrent.Future<?>> tareas = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            tareas.add(pool.submit(() -> {
                try {
                    salida.await();
                    pedidoEventListener.escucharPedidoCreado(envelope);
                } catch (Throwable e) {
                    errores.add(e);
                }
            }));
        }
        salida.countDown();
        for (java.util.concurrent.Future<?> tarea : tareas) tarea.get();
        pool.shutdown();

        assertThat(errores).as("ninguna entrega debe fallar: las copias se descartan como duplicado").isEmpty();
        assertThat(pagoRepositoryPort.buscarPorUsuarioId(usuario)).hasSize(1);
        esperarEventoDe(pedidoId);
        assertThat(eventosRestantesDe(pedidoId)).isEmpty();
    }

    @Test
    void sinNingunaColaEnlazada_ElEventoQuedaPendienteYSePublicaCuandoAparece() throws Exception {
        UUID pedidoId = UUID.randomUUID();
        String usuario = usuarioUnico();

        rabbitTemplate.send(RabbitMQConfig.EXCHANGE_PEDIDOS_EVENTS, RabbitMQConfig.ROUTING_KEY_PEDIDO_CREADO,
                mensaje(pedidoCreadoJson(UUID.randomUUID(), null, pedidoId, usuario, "3000")));

        await().atMost(Duration.ofSeconds(10)).until(() -> !pagoRepositoryPort.buscarPorUsuarioId(usuario).isEmpty());
        UUID pagoId = pagoRepositoryPort.buscarPorUsuarioId(usuario).get(0).getId().getValor();
        UUID eventoId = UUID.nameUUIDFromBytes(("pago.aprobado:" + pagoId).getBytes(StandardCharsets.UTF_8));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            EventoSalidaEntity fila = eventoSalidaRepository.findById(eventoId).orElseThrow();
            assertThat(fila.getPublicadoEn()).as("sin cola enlazada no debe darse por publicado").isNull();
            assertThat(fila.getIntentos()).as("debe haber reintentado").isGreaterThanOrEqualTo(1);
        });

        enlazarColaDePrueba();

        assertThat(validarContraSchema("pago.aprobado", esperarEventoDe(pedidoId))).isEmpty();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(eventoSalidaRepository.findById(eventoId).orElseThrow().getPublicadoEn()).isNotNull());
    }

    @Test
    void api_DeberiaListarSoloLosPagosDelUsuarioDelToken_YExigirToken() throws Exception {
        enlazarColaDePrueba();
        UUID pedidoId = UUID.randomUUID();
        String usuario = usuarioUnico();
        rabbitTemplate.send(RabbitMQConfig.EXCHANGE_PEDIDOS_EVENTS, RabbitMQConfig.ROUTING_KEY_PEDIDO_CREADO,
                mensaje(pedidoCreadoJson(UUID.randomUUID(), null, pedidoId, usuario, "3000")));
        esperarEventoDe(pedidoId);
        UUID pagoId = pagoRepositoryPort.buscarPorUsuarioId(usuario).get(0).getId().getValor();

        mockMvc.perform(get("/api/pagos").with(jwtCliente(usuario)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(pagoId.toString()))
                .andExpect(jsonPath("$[0].estado").value("APROBADO"));
        mockMvc.perform(get("/api/pagos").with(jwtCliente(usuarioUnico())))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/pagos/{id}", pagoId).with(jwtCliente(usuarioUnico()))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/pagos/{id}", pagoId).with(jwtCliente(usuario))).andExpect(status().isOk());
        mockMvc.perform(get("/api/pagos")).andExpect(status().isUnauthorized());
    }

    private void enlazarColaDePrueba() {
        colaDePrueba = "test.pagos." + UUID.randomUUID();
        // Durable y sin autoDelete: RabbitMQ 4 rechaza colas transitorias no exclusivas. Se borra en @AfterEach.
        Queue cola = new Queue(colaDePrueba, true, false, false);
        rabbitAdmin.declareQueue(cola);
        Binding binding = BindingBuilder.bind(cola).to(pagosEventsExchange).with("pago.*");
        rabbitAdmin.declareBinding(binding);
    }

    /** Lee de la cola de prueba hasta encontrar el evento de ese pedido (ignora los de otros tests). */
    private String esperarEventoDe(UUID pedidoId) {
        long limite = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < limite) {
            Message m = rabbitTemplate.receive(colaDePrueba, 500);
            if (m == null) continue;
            String json = new String(m.getBody(), StandardCharsets.UTF_8);
            if (objectMapper.readTree(json).get("payload").get("pedidoId").asString().equals(pedidoId.toString())) return json;
        }
        throw new AssertionError("No llego ningun evento para el pedido " + pedidoId);
    }

    private List<String> eventosRestantesDe(UUID pedidoId) {
        List<String> restantes = new ArrayList<>();
        Message m;
        while ((m = rabbitTemplate.receive(colaDePrueba, 500)) != null) {
            String json = new String(m.getBody(), StandardCharsets.UTF_8);
            if (objectMapper.readTree(json).get("payload").get("pedidoId").asString().equals(pedidoId.toString())) restantes.add(json);
        }
        return restantes;
    }

    private EventoSalidaEntity salidaDe(JsonNode evento) {
        return eventoSalidaRepository.findById(UUID.fromString(evento.get("eventId").asString())).orElseThrow();
    }

    private List<Error> validarContraSchema(String evento, String json) throws Exception {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("docs/events/" + evento + ".schema.json")));
        return schema.validate(json, InputFormat.JSON);
    }

    private static String usuarioUnico() {
        return "usuario-integ-" + UUID.randomUUID();
    }

    private static Message mensaje(String cuerpo) {
        return MessageBuilder.withBody(cuerpo.getBytes(StandardCharsets.UTF_8)).setContentType("application/json").build();
    }

    static String pedidoCreadoJson(UUID eventId, UUID correlationId, UUID pedidoId, String usuarioId, String total) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"pedido.creado\",\"eventVersion\":1,"
                + "\"occurredAt\":\"" + Instant.now() + "\",\"producer\":\"pedidos-service\","
                + (correlationId == null ? "" : "\"correlationId\":\"" + correlationId + "\",")
                + "\"payload\":{\"pedidoId\":\"" + pedidoId + "\",\"carritoId\":\"" + UUID.randomUUID()
                + "\",\"usuarioId\":\"" + usuarioId + "\",\"total\":" + total + ",\"items\":[{\"productoId\":\""
                + UUID.randomUUID() + "\",\"nombre\":\"Manzana\",\"precioUnitario\":" + total
                + ",\"moneda\":\"CLP\",\"cantidad\":1}]}}";
    }

    private static JwtRequestPostProcessor jwtCliente(String usuarioId) {
        return jwt().jwt(j -> j.subject(usuarioId)).authorities(new SimpleGrantedAuthority("ROLE_cliente"));
    }
}
