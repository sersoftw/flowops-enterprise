package com.flowops.orders.api;

import com.flowops.orders.application.OrderService;
import com.flowops.orders.application.OrderStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handle(Exception ex) {
        int status;
        String detail;
        if (ex instanceof OrderService.NotFound) {
            status = 404; detail = ex.getMessage();
        } else if (ex instanceof OrderStore.IdempotencyConflict) {
            status = 409; detail = ex.getMessage();
        } else if (ex instanceof IllegalArgumentException) {
            status = 400; detail = ex.getMessage();
        } else if (ex instanceof org.springframework.beans.TypeMismatchException) {
            status = 400; detail = "Invalid request parameter";
        } else if (ex instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            status = 400; detail = "Malformed request body";
        } else if (ex instanceof ErrorResponse error) {
            status = error.getStatusCode().value(); detail = "Request rejected";
        } else {
            status = 500; detail = "An unexpected server error occurred";
            org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Order API failed", ex);
        }
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        problem.setType(java.net.URI.create("urn:flowops:problem:http-" + status));
        if (ex instanceof MethodArgumentNotValidException validation) {
            problem.setProperty("errors", validation.getBindingResult().getFieldErrors().stream()
                    .map(e -> new FieldError(e.getField(), e.getDefaultMessage())).toList());
        }
        return ResponseEntity.status(status).body(problem);
    }
    record FieldError(String field, String message) { }
}
