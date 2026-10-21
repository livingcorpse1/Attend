package com.veriqra.app.web;

import com.veriqra.app.security.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * A client that hung up mid response. Tomcat reports it as an abort, and it is not a
 * failure of the application.
 */
class ClientAbortException extends IOException {
    ClientAbortException(String message) {
        super(message);
    }
}

/**
 * Converts every exception into the single JSON error envelope used by the client.
 *
 * <p>Unexpected exceptions are logged with a correlation id and reported to the
 * client as a generic message so internal details never leak to the browser.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Body returned for every error. */
    public record ErrorBody(String code, String message, int status, Instant timestamp, String path) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException ex, HttpServletRequest request) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                detail.isBlank() ? "The request payload is invalid." : detail, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorBody> handleMissingParameter(MissingServletRequestParameterException ex,
                                                            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "Required parameter '" + ex.getParameterName() + "' is missing.", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException ex,
                                                      HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_BODY", "The request body could not be parsed.", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorBody> handleUploadTooLarge(MaxUploadSizeExceededException ex,
                                                          HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE",
                "The uploaded capture is larger than the configured limit.", request);
    }

    /**
     * The browser closed the connection before the answer was written.
     *
     * <p>That happens on every navigation away from a screen that is still loading.
     * It is not a failure of the request, so it is not reported as one.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public void handleClientDisconnect(Exception ex, HttpServletRequest request) {
        log.info("The client closed the connection during {} {}", request.getMethod(), request.getRequestURI());
    }

    /** The route exists but not for this HTTP method. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorBody> handleWrongMethod(HttpRequestMethodNotSupportedException ex,
                                                       HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "That address does not accept " + request.getMethod() + ".", request);
    }

    /** Nothing is mapped to that address. */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorBody> handleNotFound(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "That address does not exist.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorBody> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to perform this action.", request);
    }

    /**
     * The database could not be reached.
     *
     * <p>This is a temporary condition, not a bug in the request, so it is reported as
     * "try again" rather than as an internal error. The reference is still logged so
     * the cause can be found.
     */
    @ExceptionHandler(CannotGetJdbcConnectionException.class)
    public ResponseEntity<ErrorBody> handleNoConnection(CannotGetJdbcConnectionException ex,
                                                        HttpServletRequest request) {
        String correlationId = java.util.UUID.randomUUID().toString();
        log.error("The database could not be reached [{}] on {} {}", correlationId,
                request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                "The server cannot reach the database right now. Please try again in a moment.", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorBody> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpected(Exception ex, HttpServletRequest request) {
        String correlationId = java.util.UUID.randomUUID().toString();
        log.error("Unhandled failure [{}] on {} {}", correlationId, request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong on our side. Reference: " + correlationId, request);
    }

    private String describe(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    private ResponseEntity<ErrorBody> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        ErrorBody body = new ErrorBody(code, message, status.value(), Instant.now(), request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }



}
