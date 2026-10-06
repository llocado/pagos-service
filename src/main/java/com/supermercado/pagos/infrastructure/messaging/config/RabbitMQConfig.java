package com.supermercado.pagos.infrastructure.messaging.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.listener.ListenerExecutionFailedException;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

/**
 * Topologia RabbitMQ de pagos-service (ver "Contrato de eventos" en el ROADMAP): un exchange topic
 * propio (pagos.events) del que es productor, y una cola propia con su dead-letter queue para
 * consumir pedido.creado de pedidos.events. El exchange de pedidos se declara de forma defensiva e
 * idempotente (pedidos-service lo declara igual): asi la cola se puede enlazar aunque pedidos no
 * haya arrancado todavia.
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_PEDIDOS_EVENTS = "pedidos.events";
    public static final String EXCHANGE_PAGOS_EVENTS = "pagos.events";

    public static final String ROUTING_KEY_PEDIDO_CREADO = "pedido.creado";
    public static final String ROUTING_KEY_PAGO_APROBADO = "pago.aprobado";
    public static final String ROUTING_KEY_PAGO_RECHAZADO = "pago.rechazado";

    public static final String QUEUE_PEDIDO_CREADO = "pagos.pedido-creado";
    private static final String DLX_PAGOS = "pagos.dlx";
    private static final String DLQ_PEDIDO_CREADO = "pagos.pedido-creado.dlq";

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public TopicExchange pedidosEventsExchange() {
        return new TopicExchange(EXCHANGE_PEDIDOS_EVENTS);
    }

    @Bean
    public TopicExchange pagosEventsExchange() {
        return new TopicExchange(EXCHANGE_PAGOS_EVENTS);
    }

    @Bean
    public DirectExchange pagosDeadLetterExchange() {
        return new DirectExchange(DLX_PAGOS);
    }

    @Bean
    public Queue pedidoCreadoDeadLetterQueue() {
        return QueueBuilder.durable(DLQ_PEDIDO_CREADO).build();
    }

    @Bean
    public Binding pedidoCreadoDeadLetterBinding() {
        return BindingBuilder.bind(pedidoCreadoDeadLetterQueue()).to(pagosDeadLetterExchange()).with(DLQ_PEDIDO_CREADO);
    }

    @Bean
    public Queue pedidoCreadoQueue() {
        return QueueBuilder.durable(QUEUE_PEDIDO_CREADO)
                .withArgument("x-dead-letter-exchange", DLX_PAGOS)
                .withArgument("x-dead-letter-routing-key", DLQ_PEDIDO_CREADO)
                .build();
    }

    @Bean
    public Binding pedidoCreadoBinding() {
        return BindingBuilder.bind(pedidoCreadoQueue()).to(pedidosEventsExchange()).with(ROUTING_KEY_PEDIDO_CREADO);
    }

    /**
     * Los reintentos son para fallos transitorios. No se reintenta un mensaje que el listener ya
     * rechazo de forma definitiva (AmqpRejectAndDontRequeueException, a veces envuelta en otra
     * excepcion) ni uno que no se puede convertir (por ejemplo, JSON ilegible): van directo a la DLQ.
     */
    @Bean
    public RabbitListenerRetrySettingsCustomizer noReintentarRechazosDefinitivos() {
        return settings -> settings.setExceptionPredicate(error -> !esIrrecuperable(error));
    }

    /**
     * Reemplaza a RejectAndDontRequeueRecoverer (el de Spring), que al agotar los reintentos escribe el
     * cuerpo del mensaje y ~80 lineas de stack trace. Este deja una sola linea con la cola, el messageId y la
     * clase de la causa (sin su texto: el de un error de JSON puede contener fragmentos del payload) y rechaza
     * igual: el mensaje va a la DLQ.
     */
    @Bean
    public MessageRecoverer recuperadorSinCuerpoEnElLog() {
        return (mensaje, causa) -> {
            log.warn("Reintentos agotados, mensaje a la DLQ cola={} messageId={} causa={}",
                    mensaje.getMessageProperties().getConsumerQueue(), mensaje.getMessageProperties().getMessageId(),
                    NestedExceptionUtils.getMostSpecificCause(causa).getClass().getSimpleName());
            throw new ListenerExecutionFailedException("Retry Policy Exhausted", new AmqpRejectAndDontRequeueException(causa), mensaje);
        };
    }

    private static boolean esIrrecuperable(Throwable error) {
        for (Throwable actual = error; actual != null; actual = actual.getCause() == actual ? null : actual.getCause()) {
            if (actual instanceof AmqpRejectAndDontRequeueException
                    || actual instanceof MessageConversionException
                    || actual instanceof org.springframework.messaging.converter.MessageConversionException
                    || actual instanceof ClassCastException
                    || actual instanceof NoSuchMethodException) {
                return true;
            }
        }
        return false;
    }
}
