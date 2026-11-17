package io.github.hamza2829.catalog.model;

import org.springframework.http.HttpStatus;

/** Expected request failure with a safe, stable API code. */
public class CatalogException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public CatalogException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
