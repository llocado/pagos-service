package com.supermercado.pagos.infrastructure.rest;

import com.supermercado.pagos.domain.exception.PagoNoEncontradoException;
import com.supermercado.pagos.infrastructure.rest.dto.ErrorResponse;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PagoNoEncontradoException.class)
    public ResponseEntity<ErrorResponse> handlePagoNoEncontrado(PagoNoEncontradoException ex) {
        log.info("Pago no encontrado: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    /** No se hace eco del valor recibido, solo del nombre del parametro. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleParametroInvalido(MethodArgumentTypeMismatchException ex) {
        log.warn("Parametro invalido en la peticion: {}", ex.getName());
        return ResponseEntity.badRequest().body(new ErrorResponse("El valor del parametro '" + ex.getName() + "' no es valido"));
    }

    /**
     * Base de datos inalcanzable: 503 con un mensaje generico y una sola linea de log (la causa, sin stack trace
     * ni detalles de conexion). Sin este handler, cada peticion dejaba ~120 lineas de traza y un 500.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ErrorResponse> handleBaseNoDisponible(Exception ex) {
        log.warn("Base de datos no disponible: {}", NestedExceptionUtils.getMostSpecificCause(ex).getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse("Servicio temporalmente no disponible"));
    }

    /**
     * La primera peticion tras caer la base reutiliza una conexion ya rota del pool y llega como JpaSystemException
     * (SQLState 57P01 "terminating connection", clase 08 de conexion o "Connection is closed"), no como CannotCreateTransactionException: se
     * trata igual que una base caida. Cualquier otra JpaSystemException sigue siendo un 500 inesperado, con su traza.
     */
    @ExceptionHandler(JpaSystemException.class)
    public ResponseEntity<ErrorResponse> handleErrorDeAccesoADatos(JpaSystemException ex) {
        if (esConexionPerdida(ex)) {
            return handleBaseNoDisponible(ex);
        }
        log.error("Error inesperado de acceso a datos", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("Error interno del servidor"));
    }

    private static boolean esConexionPerdida(Throwable error) {
        for (Throwable actual = error; actual != null; actual = actual.getCause() == actual ? null : actual.getCause()) {
            if (actual instanceof SQLException sql && sinConexion(sql)) {
                return true;
            }
        }
        return false;
    }

    /** SQLState de conexion (08) u operador (57P: la base se apago), o el "Connection is closed" de Hikari al reusar una conexion rota. */
    private static boolean sinConexion(SQLException sql) {
        String estado = sql.getSQLState();
        return (estado != null && (estado.startsWith("08") || estado.startsWith("57P")))
                || "Connection is closed".equals(sql.getMessage());
    }
}
