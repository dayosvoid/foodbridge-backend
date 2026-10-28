package com.tesa.foodbridge.shared;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorResponse(
            String code,
            String message,
            Instant timestamp
    ) {}

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse validation(MethodArgumentNotValidException e) {

        return new ErrorResponse(
                "INVALID_REQUEST",
                "Request validation failed",
                Instant.now()
        );
    }

    @ExceptionHandler(RuleViolation.class)
    ResponseEntity<ErrorResponse> rule(RuleViolation e) {

        HttpStatus status = switch (e.code()) {

            case "UNAUTHORIZED" ->
                    HttpStatus.UNAUTHORIZED;

            case "FORBIDDEN" ->
                    HttpStatus.FORBIDDEN;

            case "LISTING_NOT_FOUND",
                 "REQUEST_NOT_FOUND" ->
                    HttpStatus.NOT_FOUND;

            case "LISTING_ALREADY_RESERVED",
                 "LISTING_EXPIRED",
                 "REQUEST_NOT_PENDING",
                 "IDEMPOTENCY_KEY_REUSED" ->
                    HttpStatus.CONFLICT;

            default ->
                    HttpStatus.BAD_REQUEST;
        };

        return ResponseEntity
                .status(status)
                .body(
                        new ErrorResponse(
                                e.code(),
                                e.getMessage(),
                                Instant.now()
                        )
                );
    }
}