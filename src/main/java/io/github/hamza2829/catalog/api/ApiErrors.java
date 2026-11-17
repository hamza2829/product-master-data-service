package io.github.hamza2829.catalog.api;

import io.github.hamza2829.catalog.model.CatalogException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Emits consistent problem details for API and request-validation failures. */
@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ProblemDetail> catalogFailure(CatalogException error) {
        return ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(error.status(), error.code(), error.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpectedFailure(Exception error) {
        LOG.error("Unexpected API failure", error);
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not complete."));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body, HttpHeaders headers,
                                                               HttpStatusCode status, WebRequest request) {
        ProblemDetail response;
        if (error instanceof MaxUploadSizeExceededException) {
            response = problem(status, "FILE_TOO_LARGE", "The upload exceeds the request size limit.");
        } else if (status.is5xxServerError()) {
            LOG.error("Request handling failed", error);
            response = problem(status, "INTERNAL_ERROR", "The request could not complete.");
        } else {
            String detail = body instanceof ProblemDetail original ? original.getDetail() : null;
            response = problem(status, "INVALID_REQUEST",
                    detail == null ? "Check the request path, parameters and body." : detail);
        }
        return super.handleExceptionInternal(error, response, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code);
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            body.setProperty("traceId", traceId);
        }
        return body;
    }
}
