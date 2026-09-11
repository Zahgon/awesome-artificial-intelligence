package com.awesomeai.validator;

/**
 * Raised where CPython's {@code urllib.parse} raises {@code ValueError}.
 *
 * <p>The message text is reproduced verbatim from CPython so that the rendered
 * {@code line N: invalid URL '...' (reason)} diagnostic is byte-identical.
 */
public class InvalidUrlException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidUrlException(String message) {
        super(message);
    }
}
