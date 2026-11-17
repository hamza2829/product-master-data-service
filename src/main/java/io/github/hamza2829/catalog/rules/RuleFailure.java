package io.github.hamza2829.catalog.rules;

import io.github.hamza2829.catalog.model.RejectionCode;

/** A row-level failure that can be stored and retried. */
public class RuleFailure extends RuntimeException {

    private final RejectionCode code;

    public RuleFailure(RejectionCode code, String message) {
        super(message);
        this.code = code;
    }

    public RejectionCode code() {
        return code;
    }
}
