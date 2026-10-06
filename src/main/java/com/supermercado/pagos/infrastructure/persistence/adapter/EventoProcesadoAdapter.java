package com.supermercado.pagos.infrastructure.persistence.adapter;

import com.supermercado.pagos.application.port.EventoProcesadoPort;
import com.supermercado.pagos.infrastructure.persistence.entity.EventoProcesadoEntity;
import com.supermercado.pagos.infrastructure.persistence.repository.SpringDataEventoProcesadoRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EventoProcesadoAdapter implements EventoProcesadoPort {

    private final SpringDataEventoProcesadoRepository springDataEventoProcesadoRepository;

    @Override
    public boolean yaFueProcesado(UUID eventId) {
        return springDataEventoProcesadoRepository.existsById(eventId);
    }

    @Override
    public void marcarComoProcesado(UUID eventId) {
        EventoProcesadoEntity entity = new EventoProcesadoEntity();
        entity.setEventId(eventId);
        entity.setProcesadoEn(Instant.now());
        springDataEventoProcesadoRepository.save(entity);
    }
}
