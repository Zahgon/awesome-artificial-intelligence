package com.awesomeai.validator;

/**
 * Severity of a link-check finding.
 *
 * <p>The Python original models this as the bare strings {@code "error"} and {@code "warning"}
 * in a 2-tuple; the enum is the faithful Java equivalent.
 */
public enum Severity {
    ERROR("error"),
    WARNING("warning");

    private final String label;

    Severity(String label) {
        this.label = label;
    }

    /** The lowercase label used by the Python implementation. */
    public String label() {
        return label;
    }
}
