package com.awesomeai.validator;

/**
 * A single curated resource entry parsed out of the README.
 *
 * <p>Port of the frozen {@code @dataclass Resource} in {@code scripts/validate_readme.py}.
 * Field order and names are preserved verbatim.
 */
public record Resource(
        int line,
        String section,
        String category,
        String title,
        String url,
        String description) {
}
