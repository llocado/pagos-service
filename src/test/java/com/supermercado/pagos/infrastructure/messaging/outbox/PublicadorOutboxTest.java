package com.supermercado.pagos.infrastructure.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.supermercado.pagos.infrastructure.persistence.entity.EventoSalidaEntity;
import com.supermercado.pagos.infrastructure.persistence.repository.SpringDataEventoSalidaRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

class PublicadorOutboxTest {

    private final SpringDataEventoSalidaRepository repositorio = mock(SpringDataEventoSalidaRepository.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final PublicadorOutbox publicador = new PublicadorOutbox(repositorio, rabbitTemplate, transactionManager,
            new OutboxProperties(Duration.ofSeconds(1), 50, Duration.ofSeconds(1)));

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PublicadorOutbox.class);

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void registrarFallo_DeberiaRegistrarSoloLasPotenciasDeDos() {
        assertThat(List.of(1, 2, 4, 8, 16, 1024)).allMatch(PublicadorOutbox::registrarFallo);
        assertThat(List.of(0, -1, 3, 5, 6, 7, 9, 100)).noneMatch(PublicadorOutbox::registrarFallo);
    }

    @Test
    void publicarPendientes_DeberiaIncluirElCorrelationIdEnElWarnDeFallo_YContarElIntento() {
        UUID correlacion = UUID.randomUUID();
        EventoSalidaEntity evento = evento(correlacion, 0);
        when(repositorio.bloquearPendientes(50)).thenReturn(List.of(evento));
        doThrow(new AmqpConnectException(new RuntimeException("sin broker"))).when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any());

        publicador.publicarPendientes();

        ILoggingEvent warn = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).findFirst().orElseThrow();
        assertThat(warn.getMDCPropertyMap()).containsEntry("correlationId", correlacion.toString());
        assertThat(warn.getFormattedMessage()).contains("eventId=" + evento.getId());
        assertThat(evento.getIntentos()).isEqualTo(1);
        assertThat(evento.getPublicadoEn()).isNull();
    }

    @Test
    void publicarPendientes_DeberiaUsarElIdDelEventoComoCorrelationId_CuandoNoHayCorrelacion() {
        EventoSalidaEntity evento = evento(null, 0);
        when(repositorio.bloquearPendientes(50)).thenReturn(List.of(evento));
        doThrow(new AmqpConnectException(new RuntimeException("sin broker"))).when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any());

        publicador.publicarPendientes();

        ILoggingEvent warn = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).findFirst().orElseThrow();
        assertThat(warn.getMDCPropertyMap()).containsEntry("correlationId", evento.getId().toString());
    }

    @Test
    void publicarPendientes_DeberiaCortarElLote_CuandoElBrokerNoDisponible() {
        EventoSalidaEntity primero = evento(UUID.randomUUID(), 0);
        EventoSalidaEntity segundo = evento(UUID.randomUUID(), 0);
        when(repositorio.bloquearPendientes(50)).thenReturn(List.of(primero, segundo));
        doThrow(new AmqpConnectException(new RuntimeException("sin broker"))).when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any());

        publicador.publicarPendientes();

        verify(rabbitTemplate, times(1)).send(anyString(), anyString(), any(Message.class), any());
        assertThat(primero.getIntentos()).isEqualTo(1);
        assertThat(segundo.getIntentos()).isZero();
    }

    @Test
    void publicarPendientes_DeberiaEspaciarElWarn_CuandoElEventoYaFalloVariasVeces() {
        EventoSalidaEntity evento = evento(UUID.randomUUID(), 2); // sera el 3.o fallo: no se registra
        when(repositorio.bloquearPendientes(50)).thenReturn(List.of(evento));
        doThrow(new AmqpConnectException(new RuntimeException("sin broker"))).when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any());

        publicador.publicarPendientes();

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
        assertThat(evento.getIntentos()).isEqualTo(3);
    }

    @Test
    void publicarPendientes_DeberiaRegistrarUnaSolaLineaSinStackTrace_CuandoLaBaseNoDisponible() {
        when(repositorio.bloquearPendientes(50)).thenThrow(new DataAccessResourceFailureException("db caida"));

        publicador.publicarPendientes(); // no debe propagar la excepcion al planificador
        publicador.publicarPendientes(); // 2.o fallo consecutivo: tambien se registra
        publicador.publicarPendientes(); // 3.o: no

        List<ILoggingEvent> warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).hasSize(2);
        assertThat(warns).allMatch(e -> e.getThrowableProxy() == null);
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class), any());
    }

    private static EventoSalidaEntity evento(UUID correlacion, int intentos) {
        EventoSalidaEntity evento = new EventoSalidaEntity();
        evento.setId(UUID.randomUUID());
        evento.setEventType("pago.aprobado");
        evento.setRoutingKey("pago.aprobado");
        evento.setCorrelationId(correlacion);
        evento.setPayload("{}");
        evento.setCreadoEn(Instant.now());
        evento.setIntentos(intentos);
        return evento;
    }
}
