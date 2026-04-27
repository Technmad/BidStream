package com.bidstream.common;

import io.jsonwebtoken.JwtException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain/application exceptions to RFC 7807 problem+json responses (PDR §14.3).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Without this handler, a {@code @Valid} failure fell through to Spring's
     * {@code DefaultHandlerExceptionResolver}, which sets the response status via
     * {@code sendError(400)} - triggering a container-level forward to {@code /error}. That
     * forward re-enters Spring Security's filter chain as an ERROR dispatch, but
     * {@link com.bidstream.common.security.JwtAuthenticationFilter} (an
     * {@code OncePerRequestFilter}) skips ERROR dispatches by default, so the security context
     * reset to anonymous and {@code anyRequest().authenticated()} silently overwrote the real 400
     * with a bare 403 - hiding the validation error from every client of every {@code @Valid} DTO
     * in the app. Handling the exception here writes the response directly, before any of that
     * forwarding machinery runs.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Request validation failed");
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        problem.setProperty("errors", fieldErrors);
        return problem;
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(BidRejectedException.class)
    public ProblemDetail handleBidRejected(BidRejectedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.reason().name());
        problem.setProperty("reason", ex.reason().name());
        problem.setProperty("currentPrice", ex.currentPrice());
        problem.setProperty("minIncrement", ex.minIncrement());
        return problem;
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(JwtException.class)
    public ProblemDetail handleJwt(JwtException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}
