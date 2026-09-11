package com.awesomeai.validator;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.net.ssl.SSLException;

/**
 * Network liveness checks for resource links.
 *
 * <p>Port of {@code classify_status}, {@code classify_exception}, {@code check_link} and
 * {@code check_links} in {@code scripts/validate_readme.py}.
 */
public final class LinkChecker {

    private LinkChecker() {
    }

    public static final String USER_AGENT = "awesome-ai-resource-validator/1.0";

    private static final int TIMEOUT_SECONDS = 15;
    private static final int MAX_WORKERS = 8;

    /**
     * PARITY: Python's {@code timeout=15} is a per-socket-operation timeout. The JDK splits the
     * same budget into a connect timeout and a total-response timeout; both are set to 15s.
     *
     * <p>{@link HttpClient.Redirect#ALWAYS} rather than {@code NORMAL}, because {@code NORMAL}
     * refuses HTTPS to HTTP downgrades whereas {@code urllib} follows them.
     *
     * <p>HTTP/1.1 is pinned because {@code urllib} speaks only HTTP/1.1, and the JDK default of
     * HTTP/2 is observably different: some bot-detection front ends answer 403 over HTTP/2 and 200
     * over HTTP/1.1 for the identical request, which would report phantom broken links.
     */
    static HttpClient newClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }

    /**
     * Maps an HTTP status to a finding, or {@code null} when the link is healthy.
     *
     * <p>Branch order is significant and matches the original exactly.
     */
    public static Finding classifyStatus(int status, String url) {
        if (status == 404 || status == 410) {
            return Finding.error("broken link (" + status + "): " + url);
        }
        if (status == 401 || status == 403 || status == 429) {
            return Finding.warning("link check blocked (" + status + "): " + url);
        }
        if (status == 408) {
            return Finding.warning("link check timed out (" + status + "): " + url);
        }
        if (status >= 500) {
            return Finding.warning("remote server error (" + status + "): " + url);
        }
        if (status >= 400) {
            return Finding.error("broken link (" + status + "): " + url);
        }
        return null;
    }

    /**
     * Maps a transport-level failure to a finding.
     *
     * <p>The Python original unwraps {@code URLError.reason} before dispatching; the JDK surfaces
     * the concrete exception directly, so the equivalent concrete types are matched here.
     *
     * <p>PARITY: the {@code (reason)} parenthetical is the runtime's own exception text and will
     * differ between CPython and the JDK. Severity and the surrounding format are identical.
     */
    public static Finding classifyException(Throwable error, String url) {
        Throwable reason = unwrap(error);
        String detail = describe(reason);
        if (reason instanceof HttpTimeoutException) {
            return Finding.warning("link check timed out: " + url + " (" + detail + ")");
        }
        if (reason instanceof SSLException || reason instanceof UnknownHostException) {
            return Finding.error("unreachable link: " + url + " (" + detail + ")");
        }
        if (reason instanceof ProtocolException) {
            return Finding.warning("link check interrupted: " + url + " (" + detail + ")");
        }
        return Finding.error("unreachable link: " + url + " (" + detail + ")");
    }

    /**
     * Checks a single resource link.
     *
     * <p>Tries {@code HEAD} first and retries with {@code GET} only when the server explicitly
     * rejects the method ({@code 405} or {@code 501}). A transport failure on {@code HEAD} is
     * reported immediately and is <em>not</em> retried, matching the original control flow.
     *
     * <p>PARITY: {@code urllib} raises on status >= 400 whereas {@link HttpClient} returns it, so
     * the exception-driven Python flow is expressed here as direct status inspection. The
     * observable outcome is identical.
     */
    public static Finding checkLink(HttpClient client, Resource resource) {
        String url = resource.url();
        URI uri;
        try {
            uri = requestUri(url);
        } catch (URISyntaxException | IllegalArgumentException error) {
            return classifyException(error, url);
        }

        int status;
        try {
            status = send(client, uri, "HEAD");
        } catch (Exception error) {
            return classifyException(error, url);
        }
        if (status != 405 && status != 501) {
            return classifyStatus(status, url);
        }

        try {
            status = send(client, uri, "GET");
        } catch (Exception error) {
            return classifyException(error, url);
        }
        return classifyStatus(status, url);
    }

    /**
     * Builds the request target for a resource URL.
     *
     * <p>PARITY: {@code urllib} parses with the permissive {@code urlsplit} grammar and puts the
     * path and query on the wire verbatim, so the characters RFC 3986 forbids -- space, quote,
     * {@code < > \ ^ ` { | }} -- reach the server unchanged. {@code RESOURCE_RE} accepts every one
     * of them inside {@code https://[^)\s]+}, but {@link URI} is strict and rejects them, which
     * would report a reachable link as unreachable. Strict parsing is therefore only the fast path:
     * when it fails, the offending octets are percent-encoded and parsing is retried, which is as
     * close to sending them raw as {@link HttpRequest} allows. Non-ASCII URLs take the same route;
     * they are the one input where the JDK is deliberately better than the original, which aborts
     * on them with an unhandled {@code UnicodeEncodeError}.
     */
    static URI requestUri(String url) throws URISyntaxException {
        try {
            return new URI(url);
        } catch (URISyntaxException | IllegalArgumentException strict) {
            return new URI(percentEncodeIllegal(url));
        }
    }

    /** Unreserved punctuation plus the gen-delims and sub-delims of RFC 3986 section 2.2. */
    private static final String URI_PUNCTUATION = "-._~:/?#[]@!$&'()*+,;=";

    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private static String percentEncodeIllegal(String url) {
        StringBuilder encoded = new StringBuilder(url.length());
        for (int index = 0; index < url.length(); index++) {
            char character = url.charAt(index);
            if (character == '%' && isEscape(url, index)) {
                encoded.append(url, index, index + 3);
                index += 2;
            } else if (isUnreserved(character) || URI_PUNCTUATION.indexOf(character) >= 0) {
                encoded.append(character);
            } else {
                index = appendEncoded(encoded, url, index);
            }
        }
        return encoded.toString();
    }

    /** Appends one code point as UTF-8 escapes and returns the index of its final char. */
    private static int appendEncoded(StringBuilder encoded, String url, int index) {
        int end = index + 1;
        if (Character.isHighSurrogate(url.charAt(index))
                && end < url.length()
                && Character.isLowSurrogate(url.charAt(end))) {
            end++;
        }
        for (byte octet : url.substring(index, end).getBytes(StandardCharsets.UTF_8)) {
            encoded.append('%')
                    .append(HEX_DIGITS[(octet >> 4) & 0xF])
                    .append(HEX_DIGITS[octet & 0xF]);
        }
        return end - 1;
    }

    private static boolean isEscape(String url, int index) {
        return index + 2 < url.length()
                && isHexDigit(url.charAt(index + 1))
                && isHexDigit(url.charAt(index + 2));
    }

    private static boolean isHexDigit(char character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f')
                || (character >= 'A' && character <= 'F');
    }

    private static boolean isUnreserved(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9');
    }

    private static int send(HttpClient client, URI uri, String method)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** The {@code (errors, warnings)} pair returned by Python's {@code check_links}. */
    public record LinkResults(List<String> errors, List<String> warnings) {
    }

    /**
     * Checks every resource across a pool of eight workers, returning both lists sorted by
     * Unicode code point to match Python's {@code sorted()}.
     */
    public static LinkResults checkLinks(List<Resource> resources) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        HttpClient client = newClient();
        ExecutorService executor = Executors.newFixedThreadPool(MAX_WORKERS);
        try {
            List<Future<Finding>> futures = new ArrayList<>(resources.size());
            for (Resource resource : resources) {
                futures.add(executor.submit(() -> checkLink(client, resource)));
            }
            for (int i = 0; i < futures.size(); i++) {
                Finding finding;
                try {
                    finding = futures.get(i).get();
                } catch (ExecutionException error) {
                    finding = classifyException(error.getCause(), resources.get(i).url());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    finding = classifyException(error, resources.get(i).url());
                }
                if (finding == null) {
                    continue;
                }
                (finding.severity() == Severity.ERROR ? errors : warnings).add(finding.message());
            }
        } finally {
            executor.shutdown();
        }

        errors.sort(PythonText.CODE_POINT_ORDER);
        warnings.sort(PythonText.CODE_POINT_ORDER);
        return new LinkResults(errors, warnings);
    }

    /** Unwraps the JDK's wrapper exceptions to reach the cause that determines severity. */
    private static Throwable unwrap(Throwable error) {
        if (error instanceof ExecutionException && error.getCause() != null) {
            return error.getCause();
        }
        // A DNS or TLS failure is frequently wrapped in a plain IOException by the HTTP client.
        if (error instanceof IOException
                && !(error instanceof HttpTimeoutException)
                && !(error instanceof SSLException)
                && !(error instanceof UnknownHostException)
                && !(error instanceof ProtocolException)
                && !(error instanceof ConnectException)) {
            Throwable cause = error.getCause();
            if (cause instanceof SSLException
                    || cause instanceof UnknownHostException
                    || cause instanceof HttpTimeoutException
                    || cause instanceof ProtocolException) {
                return cause;
            }
        }
        return error;
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }
}
