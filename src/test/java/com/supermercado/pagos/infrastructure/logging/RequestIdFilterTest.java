package com.supermercado.pagos.infrastructure.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final RequestIdFilter filtro = new RequestIdFilter();
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void capturarLogs() {
        logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void limpiar() {
        logger.detachAppender(logs);
        MDC.clear();
    }

    @Test
    void deberiaReutilizarElRequestIdEntrante_YDevolverloEnLaRespuesta_CuandoTieneFormatoValido() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        request.addHeader(RequestIdFilter.HEADER, "abc12345-trace_ID");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuranteLaPeticion = new AtomicReference<>();

        filtro.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                mdcDuranteLaPeticion.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        });

        assertThat(mdcDuranteLaPeticion.get()).isEqualTo("abc12345-trace_ID");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc12345-trace_ID");
    }

    @Test
    void deberiaGenerarUnIdNuevo_CuandoNoHayHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filtro.doFilter(new MockHttpServletRequest("GET", "/api/x"), response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches(UUID_REGEX);
    }

    @Test
    void deberiaDescartarElIdEntrante_CuandoTrae_SaltosDeLineaOCaracteresInvalidos() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        request.addHeader(RequestIdFilter.HEADER, "falso\r\nERROR 2026 admin inicio sesion");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filtro.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches(UUID_REGEX);
        assertThat(logs.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("falso"));
    }

    @Test
    void deberiaDescartarElIdEntrante_CuandoEsDemasiadoCortoOLargo() throws Exception {
        for (String invalido : new String[] {"corto", "x".repeat(65)}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
            request.addHeader(RequestIdFilter.HEADER, invalido);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filtro.doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader(RequestIdFilter.HEADER)).matches(UUID_REGEX);
        }
    }

    @Test
    void deberiaLimpiarElMdc_AlTerminarLaPeticion() throws Exception {
        filtro.doFilter(new MockHttpServletRequest("GET", "/api/x"), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void deberiaRegistrarMetodoRutaYEstado_SinQueryStringNiHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/carrito");
        request.setQueryString("token=secreto123");
        request.addHeader("Authorization", "Bearer eyJsecreto");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(404);

        filtro.doFilter(request, response, new MockFilterChain());

        assertThat(logs.list).hasSize(1);
        String linea = logs.list.get(0).getFormattedMessage();
        assertThat(linea).startsWith("GET /api/carrito -> 404 (");
        assertThat(linea).doesNotContain("secreto");
    }

    @Test
    void deberiaRegistrarElErrorConElRequestId_ReportarEstado500_YPropagarLaExcepcion_CuandoElFiltroSiguienteFalla() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        request.addHeader(RequestIdFilter.HEADER, "abc12345-trace_ID");
        MockHttpServletResponse response = new MockHttpServletResponse();
        IllegalStateException fallo = new IllegalStateException("base de datos no disponible");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filtro.doFilter(request, response, (req, res) -> {
            throw fallo;
        })).isSameAs(fallo);

        assertThat(logs.list).hasSize(2);
        ILoggingEvent error = logs.list.get(0);
        assertThat(error.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
        assertThat(error.getFormattedMessage()).contains("error no controlado").contains("base de datos no disponible");
        assertThat(error.getMDCPropertyMap()).containsEntry(RequestIdFilter.MDC_KEY, "abc12345-trace_ID");
        assertThat(logs.list.get(1).getFormattedMessage()).startsWith("GET /api/x -> 500 (");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void deberiaReportarEstado500_YPropagarElError_CuandoElFiltroSiguienteLanzaUnError() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        StackOverflowError error = new StackOverflowError("simulado");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filtro.doFilter(request, response, (req, res) -> {
            throw error;
        })).isSameAs(error);

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getFormattedMessage()).startsWith("GET /api/x -> 500 (");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
