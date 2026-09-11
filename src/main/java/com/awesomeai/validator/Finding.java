package com.awesomeai.validator;

/**
 * A classified link-check result.
 *
 * <p>Equivalent to the Python {@code tuple[str, str] | None} returned by
 * {@code classify_status} / {@code classify_exception} / {@code check_link};
 * {@code null} stands in for Python's {@code None}.
 */
public record Finding(Severity severity, String message) {

    public static Finding error(String message) {
        return new Finding(Severity.ERROR, message);
    }

    public static Finding warning(String message) {
        return new Finding(Severity.WARNING, message);
    }
}
