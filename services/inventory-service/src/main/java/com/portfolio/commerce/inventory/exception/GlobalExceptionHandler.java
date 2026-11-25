package com.portfolio.commerce.inventory.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({ProductNotFoundException.class, InventoryNotFoundException.class, ReservationNotFoundException.class})
    public ProblemDetail handleNotFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", exception.getMessage());
    }

    @ExceptionHandler(DuplicateSkuException.class)
    public ProblemDetail handleDuplicateSku(DuplicateSkuException exception) {
        return problem(HttpStatus.CONFLICT, "Duplicate SKU", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getFieldErrors().forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more request fields are invalid");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid operation", exception.getMessage());
    }

    @ExceptionHandler(ArithmeticException.class)
    public ProblemDetail handleOverflow(ArithmeticException exception) {
        return problem(HttpStatus.CONFLICT, "Inventory limit exceeded",
                "The requested operation exceeds the supported quantity");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException exception) {
        return problem(HttpStatus.CONFLICT, "Concurrent modification",
                "Inventory was modified by another transaction. Reload the resource before trying again.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException exception) {
        return problem(HttpStatus.CONFLICT, "Data integrity conflict", "The operation violates a database constraint");
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException exception) {
        return problem(HttpStatus.CONFLICT, "Insufficient stock", exception.getMessage());
    }

    @ExceptionHandler(DuplicateReservationException.class)
    public ProblemDetail handleDuplicateReservation(DuplicateReservationException exception) {
        return problem(HttpStatus.CONFLICT, "Duplicate reservation", exception.getMessage());
    }

    @ExceptionHandler(InvalidReservationStateException.class)
    public ProblemDetail handleInvalidReservationState(InvalidReservationStateException exception) {
        return problem(HttpStatus.CONFLICT, "Invalid reservation state", exception.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
