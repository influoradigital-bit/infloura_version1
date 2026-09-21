package com.influora.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.influora.service.ErrorLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * [EV-181] The error-code half of the fix: a row-lock timeout is a 409, never a 500.
 *
 * <p>Meera measured a 500 {@code PessimisticLockingFailureException} reaching the caller on two
 * flows — the loser of two concurrent deliverable approvals, and an approve racing a refund —
 * because nothing in {@link GlobalExceptionHandler} mapped it and it fell through to the generic
 * {@code Exception} handler. Every money path here serializes writers with real row locks, so this
 * is not an exotic case.
 *
 * <p>Two things are asserted, and the second is the one that actually fails if someone writes the
 * handler carelessly: the response itself, AND that Spring's own {@link
 * ExceptionHandlerMethodResolver} — the component {@code @RestControllerAdvice} dispatch really
 * uses — picks this method for a {@link CannotAcquireLockException} (the concrete type Hibernate's
 * MySQL dialect + Spring's exception translation produce from error 1205). Calling the handler
 * method directly would prove only that the method body returns 409, not that the annotation ever
 * routes anything to it.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerLockFailureTest {

    @Mock private ErrorLogService errorLogService;

    @Test
    @DisplayName("a row-lock timeout is answered 409 CONCURRENT_MODIFICATION, not 500")
    void lockTimeoutIsAConflictNotAServerError() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(errorLogService);

        ResponseEntity<ApiResponse<Void>> response =
                handler.handlePessimisticLockingFailure(
                        new CannotAcquireLockException("Lock wait timeout exceeded; try restarting transaction"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().error());
        assertEquals("CONCURRENT_MODIFICATION", response.getBody().error().code());
    }

    @Test
    @DisplayName(
            "Spring's own handler resolution routes a MySQL 1205 lock timeout to that handler — not"
                    + " to the generic 500 one")
    void springResolvesTheLockTimeoutToTheConflictHandler() throws Exception {
        ExceptionHandlerMethodResolver resolver =
                new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

        // CannotAcquireLockException is what Spring translates jakarta.persistence.LockTimeoutException
        // (MySQL 1205 / 1213) into. If the handler were registered only for some unrelated type, or
        // if a broader handler out-ranked it, this would resolve to handleGeneric instead.
        assertEquals(
                "handlePessimisticLockingFailure",
                resolver.resolveMethod(new CannotAcquireLockException("Lock wait timeout exceeded")).getName(),
                "a lock timeout must resolve to the 409 handler");
        assertEquals(
                "handlePessimisticLockingFailure",
                resolver
                        .resolveMethod(new PessimisticLockingFailureException("deadlock victim"))
                        .getName(),
                "a deadlock victim must resolve to the same 409 handler");
    }
}
