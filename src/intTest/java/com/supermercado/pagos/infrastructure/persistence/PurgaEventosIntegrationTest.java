package com.supermercado.pagos.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.supermercado.pagos.application.usecase.PurgarEventosAntiguosUseCase;
import com.supermercado.pagos.infrastructure.persistence.entity.EventoProcesadoEntity;
import com.supermercado.pagos.infrastructure.persistence.entity.EventoSalidaEntity;
import com.supermercado.pagos.infrastructure.persistence.repository.SpringDataEventoSalidaRepository;
import com.supermercado.pagos.infrastructure.persistence.repository.SpringDataEventoProcesadoRepository;
import com.supermercado.pagos.infrastructure.scheduling.PurgaEventosJob;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Retencion contra un Postgres real: lo viejo se borra y lo reciente se conserva, y un evento del outbox sin publicar jamas se descarta.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.port=1"})
class PurgaEventosIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private PurgarEventosAntiguosUseCase purgarEventosAntiguosUseCase;
    @Autowired
    private PurgaEventosJob purgaEventosJob;
    @Autowired
    private SpringDataEventoProcesadoRepository eventoProcesadoRepository;
    @Autowired
    private SpringDataEventoSalidaRepository eventoSalidaRepository;

    @BeforeEach
    void limpiar() {
        eventoProcesadoRepository.deleteAll();
        eventoSalidaRepository.deleteAll();
    }

    private UUID procesado(Instant cuando) {
        EventoProcesadoEntity e = new EventoProcesadoEntity();
        e.setEventId(UUID.randomUUID());
        e.setProcesadoEn(cuando);
        return eventoProcesadoRepository.save(e).getEventId();
    }

    private UUID salida(Instant creado, Instant publicado) {
        EventoSalidaEntity e = new EventoSalidaEntity();
        e.setId(UUID.randomUUID());
        e.setEventType("pago.aprobado");
        e.setRoutingKey("pago.aprobado");
        e.setPayload("{}");
        e.setCreadoEn(creado);
        e.setPublicadoEn(publicado);
        return eventoSalidaRepository.saveAndFlush(e).getId();
    }

    private static Instant hace(long dias) {
        return Instant.now().minus(dias, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
    }

    @Test
    void eventosProcesados_SeBorraLoAnteriorAlPlazo_YSeConservaLoReciente() {
        UUID viejo = procesado(hace(31));
        UUID justoDentro = procesado(hace(29));
        UUID reciente = procesado(Instant.now());

        var resultado = purgarEventosAntiguosUseCase.execute(Duration.ofDays(30), Duration.ofDays(7), 1000);

        assertThat(resultado.procesados()).isEqualTo(1);
        assertThat(eventoProcesadoRepository.existsById(viejo)).isFalse();
        assertThat(eventoProcesadoRepository.existsById(justoDentro)).isTrue();
        assertThat(eventoProcesadoRepository.existsById(reciente)).isTrue();
    }

    @Test
    void eventosSalida_SoloSeBorraLoYaPublicado_NuncaLoPendiente() {
        UUID publicadoViejo = salida(hace(40), hace(39));
        UUID publicadoReciente = salida(hace(5), hace(4));
        UUID pendienteViejisimo = salida(hace(400), null);

        var resultado = purgarEventosAntiguosUseCase.execute(Duration.ofDays(30), Duration.ofDays(7), 1000);

        assertThat(resultado.salida()).isEqualTo(1);
        assertThat(eventoSalidaRepository.existsById(publicadoViejo)).isFalse();
        assertThat(eventoSalidaRepository.existsById(publicadoReciente)).isTrue();
        assertThat(eventoSalidaRepository.existsById(pendienteViejisimo)).isTrue();
    }

    @Test
    void conMasFilasQueElLote_BorraEnVariasPasadas() {
        for (int i = 0; i < 25; i++) {
            procesado(hace(60));
        }
        UUID reciente = procesado(Instant.now());

        var resultado = purgarEventosAntiguosUseCase.execute(Duration.ofDays(30), Duration.ofDays(7), 10);

        assertThat(resultado.procesados()).isEqualTo(25);
        assertThat(eventoProcesadoRepository.count()).isEqualTo(1);
        assertThat(eventoProcesadoRepository.existsById(reciente)).isTrue();
    }

    @Test
    void elJobProgramado_UsaLosPlazosConfigurados() {
        UUID viejo = procesado(hace(31));
        UUID reciente = procesado(hace(1));

        purgaEventosJob.purgar();

        assertThat(eventoProcesadoRepository.existsById(viejo)).isFalse();
        assertThat(eventoProcesadoRepository.existsById(reciente)).isTrue();
    }
}
