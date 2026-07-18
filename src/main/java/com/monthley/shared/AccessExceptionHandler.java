package com.monthley.shared;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AccessExceptionHandler {

    @ExceptionHandler(Access.AccessDeniedException.class)
    ResponseEntity<Map<String, String>> denied(Access.AccessDeniedException e) {
        return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
    }
}
