package com.supermercado.pagos.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/** Un plazo de 0 dias borraria la idempotencia de eventos recien procesados: el servicio no debe arrancar asi. */
class PurgaPropertiesTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Habilitar.class)
            .withPropertyValues("purga.cron=0 30 3 * * *", "purga.eventos-procesados-dias=30",
                    "purga.eventos-salida-dias=7", "purga.lote=1000");

    @EnableConfigurationProperties(PurgaProperties.class)
    static class Habilitar {
    }

    @Test
    void valoresValidos_Arrancan() {
        contexto.run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(PurgaProperties.class).eventosProcesadosDias()).isEqualTo(30);
        });
    }

    @Test
    void plazosOLoteEnCeroONegativos_ImpidenArrancar() {
        contexto.withPropertyValues("purga.eventos-procesados-dias=0").run(c -> assertThat(c).hasFailed());
        contexto.withPropertyValues("purga.eventos-salida-dias=-1").run(c -> assertThat(c).hasFailed());
        contexto.withPropertyValues("purga.lote=0").run(c -> assertThat(c).hasFailed());
    }

    @Test
    void cronVacio_ImpideArrancar() {
        contexto.withPropertyValues("purga.cron=").run(c -> assertThat(c).hasFailed());
    }
}
