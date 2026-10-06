# pagos-service

Pago simulado del proyecto supermercado: consume `pedido.creado` y publica `pago.aprobado` o `pago.rechazado`.
Reglas compartidas con el resto del proyecto en `../CLAUDE.md` (arquitectura hexagonal, commits, verificacion,
logging, pruebas de fallo, calidad) — este archivo solo cubre lo especifico de este repo.

Diseno completo del proyecto: `../app-productos/docs/ROADMAP.md`.

## Stack

- Spring Boot 4.1.0, Java 21, Gradle.
- Postgres 16 (contenedor `pagos-postgres`, puerto **5435**), Liquibase.
- Spring Security como OAuth2 Resource Server contra Keycloak (`supermercado-infra`, realm `supermercado`).
- RabbitMQ (via `supermercado-infra`): consume `pedido.creado` (cola `pagos.pedido-creado`) y publica en `pagos.events`.
- Puerto de la app: **8083**.

## Comandos

```bash
docker compose up -d      # Postgres de este servicio
./gradlew bootRun          # arranca en localhost:8083
./gradlew test             # unitarios
./gradlew intTest          # integracion: Testcontainers (Postgres + RabbitMQ)
./gradlew jacocoFullReport   # cobertura test + intTest -> build/reports/jacoco/jacocoFullReport/index.html
./gradlew pitest              # tests de mutacion (solo unitarios) -> build/reports/pitest/index.html
./gradlew sonar              # analisis SonarQube (ver ../CLAUDE.md: requiere SONAR_TOKEN y el perfil `calidad`)
```

## Decisiones propias de este servicio

- Pago **simulado y determinista**: se rechaza si `total > pagos.simulacion.umbral-rechazo` (100000 CLP por defecto).
- **Outbox**: el evento de salida se guarda en `eventos_salida` en la misma transaccion que el pago y `PublicadorOutbox`
  lo publica con confirmacion y `mandatory`; solo se marca publicado si alguna cola lo recibio. **Todo evento que este
  servicio publique necesita al menos una cola enlazada en algun consumidor**, o quedara pendiente para siempre.
- Idempotente por `eventId` y por pedido (`pagos.pedido_id` unico). Un choque de unicidad concurrente se comprueba
  (`yaProcesado`) y, si era un duplicado, se descarta; si no, se propaga.
- La API es **de solo lectura**: los pagos solo nacen al consumir `pedido.creado`. El usuario sale siempre del JWT.
- `eventos_salida` y `eventos_procesados` crecen sin limite (retencion pendiente, ver `../docs/seguridad-pendiente.md`).

## Estado del repo

- Repo git local, rama `main`, sin remoto todavia (el usuario crea el repo en GitHub y hace el push).
