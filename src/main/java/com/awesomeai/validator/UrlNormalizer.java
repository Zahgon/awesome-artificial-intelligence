package com.awesomeai.validator;

import java.util.Locale;

/**
 * Canonicalises resource URLs so that cosmetic differences do not hide duplicates.
 *
 * <p>Port of {@code normalize_url} in {@code scripts/validate_readme.py}.
 */
public final class UrlNormalizer {

    private UrlNormalizer() {
    }

    /**
     * Normalises a URL for duplicate detection.
     *
     * <p>Lowercases scheme and host, drops userinfo and the default HTTPS port, strips every
     * trailing slash from the path (empty becomes {@code /}), preserves the query and always
     * discards the fragment.
     *
     * @throws InvalidUrlException where CPython raises {@code ValueError}
     */
    public static String normalize(String url) {
        PythonUrl.SplitResult parts = PythonUrl.urlsplit(url);

        String hostname = parts.hostname();
        hostname = hostname == null ? "" : hostname.toLowerCase(Locale.ROOT);

        Integer port = parts.port();
        String scheme = parts.scheme().toLowerCase(Locale.ROOT);
        // Python treats port 0 as falsy, so it is dropped alongside the default HTTPS port.
        if (port != null && port != 0 && !(scheme.equals("https") && port == 443)) {
            hostname = hostname + ":" + port;
        }

        String path = PythonText.rstrip(parts.path(), "/");
        if (path.isEmpty()) {
            path = "/";
        }

        return PythonUrl.urlunsplit(scheme, hostname, path, parts.query(), "");
    }
}
