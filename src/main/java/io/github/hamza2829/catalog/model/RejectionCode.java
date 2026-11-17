package io.github.hamza2829.catalog.model;

/** Stable reason codes for dead letters, log filters and metric labels. */
public enum RejectionCode {
    INVALID_RECORD,
    AMBIGUOUS_FIELD,
    MISSING_REQUIRED_FIELD,
    INVALID_ARTICLE,
    INVALID_NUMBER,
    OUT_OF_RANGE,
    INVALID_PROTECTION_CLASS,
    INVALID_UNIT,
    INVALID_STATUS,
    UNKNOWN_CATEGORY,
    DUPLICATE_ARTICLE,
    ILLEGAL_LIFECYCLE_TRANSITION,
    STALE_RETRY
}
