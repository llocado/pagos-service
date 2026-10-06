package com.supermercado.pagos.infrastructure.config;

import com.supermercado.pagos.domain.model.PoliticaDePago;
import com.supermercado.pagos.infrastructure.messaging.outbox.OutboxProperties;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({PagosConfig.SimulacionProperties.class, OutboxProperties.class})
public class PagosConfig {

    /** pagos.simulacion.*: umbral por encima del cual el pago simulado se rechaza. */
    @ConfigurationProperties(prefix = "pagos.simulacion")
    public record SimulacionProperties(BigDecimal umbralRechazo) {
    }

    @Bean
    public PoliticaDePago politicaDePago(SimulacionProperties propiedades) {
        return new PoliticaDePago(propiedades.umbralRechazo());
    }
}
