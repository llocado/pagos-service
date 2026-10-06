package com.supermercado.pagos.infrastructure.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.listener.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.retry.RetryPolicySettings;

class RabbitMQConfigTest {

    private Predicate<Throwable> seReintenta;

    @BeforeEach
    void setUp() {
        RetryPolicySettings settings = new RetryPolicySettings();
        new RabbitMQConfig().noReintentarRechazosDefinitivos().customize(settings);
        seReintenta = settings.getExceptionPredicate();
    }

    @Test
    void deberiaReintentar_UnFalloTransitorio_AunqueVengaEnvuelto() {
        assertThat(seReintenta.test(new IllegalStateException("base de datos no disponible"))).isTrue();
        assertThat(seReintenta.test(new RuntimeException("falla", new IllegalStateException("db")))).isTrue();
    }

    @Test
    void noDeberiaReintentar_UnRechazoDefinitivo_AunqueVengaEnvuelto() {
        AmqpRejectAndDontRequeueException rechazo = new AmqpRejectAndDontRequeueException("payload invalido");

        assertThat(seReintenta.test(rechazo)).isFalse();
        assertThat(seReintenta.test(new RuntimeException("falla", rechazo))).isFalse();
    }

    @Test
    void noDeberiaReintentar_UnMensajeQueNoSePuedeConvertir() {
        assertThat(seReintenta.test(new RuntimeException("falla", new MessageConversionException("json roto")))).isFalse();
    }

    @Test
    void recuperador_DeberiaRechazarSinReencolar_YNoLoguearElCuerpoNiElTextoDeLaCausa() {
        Logger logger = (Logger) LoggerFactory.getLogger(RabbitMQConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MessageProperties propiedades = new MessageProperties();
            propiedades.setMessageId("msg-1");
            propiedades.setConsumerQueue("pagos.pedido-creado");
            Message mensaje = new Message("{\"dato\":\"secreto-del-payload\"}".getBytes(), propiedades);
            Throwable causa = new RuntimeException("fragmento secreto-del-payload", new IllegalStateException("fragmento secreto-del-payload"));

            assertThatThrownBy(() -> new RabbitMQConfig().recuperadorSinCuerpoEnElLog().recover(mensaje, causa))
                    .isInstanceOf(ListenerExecutionFailedException.class)
                    .hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class);

            assertThat(appender.list).hasSize(1);
            String linea = appender.list.get(0).getFormattedMessage();
            assertThat(linea).contains("cola=pagos.pedido-creado", "messageId=msg-1", "causa=IllegalStateException")
                    .doesNotContain("secreto-del-payload");
            assertThat(appender.list.get(0).getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
