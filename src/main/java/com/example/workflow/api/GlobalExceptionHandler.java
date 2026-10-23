package com.example.workflow.api;

import com.example.workflow.domain.InvalidWorkflowException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidWorkflowException.class)
    public ResponseEntity<ProblemDetail> handleInvalidWorkflow(InvalidWorkflowException ex) {
        return invalid("Invalid workflow definition", ex.errors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        return invalid("Request validation failed", errors);
    }

    private ResponseEntity<ProblemDetail> invalid(String detail, List<String> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }
}
