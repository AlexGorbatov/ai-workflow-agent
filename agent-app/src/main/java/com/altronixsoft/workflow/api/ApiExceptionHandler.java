package com.altronixsoft.workflow.api;

import com.altronixsoft.workflow.approval.ApprovalConflictException;
import com.altronixsoft.workflow.approval.ApprovalTaskNotFoundException;
import com.altronixsoft.workflow.approval.UnprocessableDecisionException;
import com.altronixsoft.workflow.engine.InstanceNotFoundException;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Domain errors as RFC 9457 problem details. Ahead of Spring's own handler so that a validation error says
 * which rule failed instead of "Invalid request content".
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getAllErrors().stream()
                // a cross-field rule (@AssertTrue) states itself; a field rule needs the field's name
                .map(error -> error instanceof FieldError field && !"AssertTrue".equals(field.getCode())
                        ? field.getField() + " " + field.getDefaultMessage()
                        : error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler({InstanceNotFoundException.class, ApprovalTaskNotFoundException.class})
    ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ApprovalConflictException.class)
    ProblemDetail conflict(ApprovalConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(UnprocessableDecisionException.class)
    ProblemDetail unprocessable(UnprocessableDecisionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }
}
