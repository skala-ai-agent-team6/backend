package com.skala.backend.repair;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import java.util.Map;

public class ApiError extends RuntimeException {
    final int status;
    final String code;
    public ApiError(int status, String code) { super(code); this.status=status; this.code=code; }
    public static ApiError bad(String code) { return new ApiError(400,code); }
    public static ApiError conflict(String code) { return new ApiError(409,code); }
    public static ApiError missing() { return new ApiError(404,"NOT_FOUND"); }
    public static ApiError denied() { return new ApiError(403,"FORBIDDEN"); }
}
@RestControllerAdvice
class Errors {
    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> business(ApiError e) { return ResponseEntity.status(e.status).body(Map.of("code",e.code,"status",e.status)); }
    @ExceptionHandler({MethodArgumentNotValidException.class, org.springframework.web.method.annotation.HandlerMethodValidationException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.multipart.MaxUploadSizeExceededException.class, org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT","status",400)); }
    @ExceptionHandler({DataIntegrityViolationException.class, org.springframework.dao.ConcurrencyFailureException.class})
    ResponseEntity<?> conflict(Exception e) { return ResponseEntity.status(409).body(Map.of("code","CONCURRENT_OR_CONSTRAINT_CONFLICT","status",409)); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> other(Exception e) { return ResponseEntity.internalServerError().body(Map.of("code","INTERNAL_ERROR","status",500)); }
}
