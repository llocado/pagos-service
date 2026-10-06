package com.supermercado.pagos.infrastructure.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Asigna un id a cada peticion (el X-Request-Id entrante si es valido, o uno
 * nuevo), lo deja en el MDC para que aparezca en todos los logs de esa
 * peticion y lo devuelve en el header de respuesta, para poder buscar un
 * error reportado por un cliente con un solo grep. Tambien registra una linea
 * por peticion (metodo, ruta, estado, duracion). Corre antes de Spring
 * Security, asi tambien quedan registrados los 401/403.
 *
 * Seguridad: el id entrante solo se acepta con un formato estricto (evita
 * inyectar saltos de linea o texto arbitrario en los logs). No se registra el
 * query string ni ningun header de autorizacion.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern FORMATO_VALIDO = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String entrante = request.getHeader(HEADER);
        String requestId = entrante != null && FORMATO_VALIDO.matcher(entrante).matches()
                ? entrante
                : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long inicio = System.nanoTime();
        boolean completado = false;
        try {
            chain.doFilter(request, response);
            completado = true;
        } catch (ServletException | IOException | RuntimeException e) {
            // El stack trace lo escribe Tomcat al salir de este filtro, cuando el MDC ya no existe: esta
            // linea deja el requestId junto a la causa. Tomcat responde 500, aunque aqui el estado aun no lo refleje.
            log.error("{} {} -> error no controlado: {}", request.getMethod(), request.getRequestURI(), e.toString());
            throw e;
        } finally {
            long duracionMs = (System.nanoTime() - inicio) / 1_000_000;
            // Si no termino con normalidad (excepcion o Error, p. ej. OutOfMemoryError) Tomcat responde 500.
            int estado = completado ? response.getStatus() : 500;
            log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), estado, duracionMs);
            MDC.remove(MDC_KEY);
        }
    }
}
