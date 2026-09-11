package com.awesomeai.validator;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A faithful port of the pieces of CPython's {@code urllib.parse} used by the validator.
 *
 * <p>PARITY (important): {@link java.net.URI} is deliberately <em>not</em> used. {@code URI} is
 * RFC-3986-strict and rejects many URLs that CPython's intentionally permissive {@code urlsplit}
 * accepts (unencoded {@code | ^ { } [ ]}, raw non-ASCII, and so on). Routing normalisation through
 * {@code URI} would turn perfectly good README entries into spurious {@code invalid URL} errors,
 * so the CPython algorithm is reproduced directly instead.
 */
public final class PythonUrl {

    private PythonUrl() {
    }

    private static final String SCHEME_CHARS =
            "abcdefghijklmnopqrstuvwxyz"
            + "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            + "0123456789"
            + "+-.";

    /** CPython {@code urllib.parse.uses_netloc}. */
    private static final Set<String> USES_NETLOC = new HashSet<>(Arrays.asList(
            "", "ftp", "http", "gopher", "nntp", "telnet",
            "imap", "wais", "file", "mms", "https", "shttp",
            "snews", "prospero", "rtsp", "rtsps", "rtspu", "rsync",
            "svn", "svn+ssh", "sftp", "nfs", "git", "git+ssh",
            "ws", "wss", "itms-services"));

    // CPython: re.match(r"\A[vV][a-fA-F0-9]+\..+\z", host). No DOTALL there, so '.' must reject
    // newlines, and Matcher.matches() supplies the \A..\z anchoring.
    private static final Pattern IPV_FUTURE = Pattern.compile("[vV][a-fA-F0-9]+\\..+");

    /**
     * Result of {@link #urlsplit(String)}, exposing the same accessors the validator relies on.
     */
    public record SplitResult(String scheme, String netloc, String path, String query, String fragment) {

        /** Raw {@code (hostname, port)} pair, before validation. Mirrors CPython {@code _hostinfo}. */
        private String[] hostinfo() {
            String hostinfo = netloc;
            int at = netloc.lastIndexOf('@');
            if (at >= 0) {
                hostinfo = netloc.substring(at + 1);
            }
            String host;
            String port;
            int open = hostinfo.indexOf('[');
            if (open >= 0) {
                String bracketed = hostinfo.substring(open + 1);
                int close = bracketed.indexOf(']');
                if (close >= 0) {
                    host = bracketed.substring(0, close);
                    String rest = bracketed.substring(close + 1);
                    int colon = rest.indexOf(':');
                    port = colon >= 0 ? rest.substring(colon + 1) : "";
                } else {
                    host = bracketed;
                    port = "";
                }
            } else {
                int colon = hostinfo.indexOf(':');
                if (colon >= 0) {
                    host = hostinfo.substring(0, colon);
                    port = hostinfo.substring(colon + 1);
                } else {
                    host = hostinfo;
                    port = "";
                }
            }
            return new String[] {host, port.isEmpty() ? null : port};
        }

        /**
         * CPython {@code SplitResult.hostname}: lowercased, userinfo and port removed,
         * {@code null} when empty. An IPv6 zone id after {@code %} keeps its original case.
         */
        public String hostname() {
            String host = hostinfo()[0];
            if (host == null || host.isEmpty()) {
                return null;
            }
            int percent = host.indexOf('%');
            if (percent < 0) {
                return host.toLowerCase(java.util.Locale.ROOT);
            }
            return host.substring(0, percent).toLowerCase(java.util.Locale.ROOT) + host.substring(percent);
        }

        /**
         * CPython {@code SplitResult.port}.
         *
         * @return the port, or {@code null} when absent
         * @throws InvalidUrlException when the port is not an ASCII integer, or is out of range
         */
        public Integer port() {
            String port = hostinfo()[1];
            if (port == null) {
                return null;
            }
            if (!isAsciiDigits(port)) {
                throw new InvalidUrlException(
                        "Port could not be cast to integer value as " + pythonRepr(port));
            }
            int value;
            try {
                value = Integer.parseInt(port);
            } catch (NumberFormatException overflow) {
                throw new InvalidUrlException("Port out of range 0-65535");
            }
            if (value < 0 || value > 65535) {
                throw new InvalidUrlException("Port out of range 0-65535");
            }
            return value;
        }
    }

    /**
     * CPython {@code urllib.parse.urlsplit} with {@code allow_fragments=True}.
     */
    public static SplitResult urlsplit(String input) {
        String url = lstripC0ControlOrSpace(input);
        // CPython strips tab/CR/LF anywhere in the URL before parsing.
        url = url.replace("\t", "").replace("\r", "").replace("\n", "");

        String scheme = "";
        String netloc = "";
        String query = "";
        String fragment = "";

        int colon = url.indexOf(':');
        if (colon > 0 && isAsciiAlpha(url.charAt(0))) {
            boolean valid = true;
            for (int i = 0; i < colon; i++) {
                if (SCHEME_CHARS.indexOf(url.charAt(i)) < 0) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                scheme = url.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
                url = url.substring(colon + 1);
            }
        }

        if (url.startsWith("//")) {
            int delim = url.length();
            for (char c : new char[] {'/', '?', '#'}) {
                int found = url.indexOf(c, 2);
                if (found >= 0) {
                    delim = Math.min(delim, found);
                }
            }
            netloc = url.substring(2, delim);
            url = url.substring(delim);
            boolean hasOpen = netloc.indexOf('[') >= 0;
            boolean hasClose = netloc.indexOf(']') >= 0;
            if (hasOpen != hasClose) {
                throw new InvalidUrlException("Invalid IPv6 URL");
            }
            if (hasOpen) {
                checkBracketedNetloc(netloc);
            }
        }

        int hash = url.indexOf('#');
        if (hash >= 0) {
            fragment = url.substring(hash + 1);
            url = url.substring(0, hash);
        }
        int question = url.indexOf('?');
        if (question >= 0) {
            query = url.substring(question + 1);
            url = url.substring(0, question);
        }

        checkNetloc(netloc);
        return new SplitResult(scheme, netloc, url, query, fragment);
    }

    /**
     * CPython {@code urllib.parse.urlunsplit}.
     *
     * <p>Note that an empty query emits no {@code ?} and an empty fragment emits no {@code #}.
     */
    public static String urlunsplit(String scheme, String netloc, String path, String query, String fragment) {
        // CPython distinguishes "no authority" (None) from "empty authority" ("") and emits '//'
        // only for the latter. A Java String cannot carry that bit, so it is recovered here.
        boolean hasAuthority = !netloc.isEmpty()
                || (!scheme.isEmpty()
                        && USES_NETLOC.contains(scheme)
                        && (path.isEmpty() || path.startsWith("/")));
        String url = path;
        if (hasAuthority) {
            if (!url.isEmpty() && !url.startsWith("/")) {
                url = "/" + url;
            }
            url = "//" + netloc + url;
        } else if (url.startsWith("//")) {
            // Without this the result would re-parse with 'path' captured as the authority.
            url = "//" + url;
        }
        if (!scheme.isEmpty()) {
            url = scheme + ":" + url;
        }
        if (!query.isEmpty()) {
            url = url + "?" + query;
        }
        if (!fragment.isEmpty()) {
            url = url + "#" + fragment;
        }
        return url;
    }

    /**
     * CPython {@code urllib.parse._checknetloc}: rejects netlocs where NFKC normalisation would
     * introduce a delimiter (IDNA applies NFKC equivalence, so {@code \u2100} expanding to
     * {@code a/c} would silently change the authority).
     */
    private static void checkNetloc(String netloc) {
        if (netloc.isEmpty() || isAscii(netloc)) {
            return;
        }
        String stripped = netloc.replace("@", "").replace(":", "").replace("#", "").replace("?", "");
        String normalized = Normalizer.normalize(stripped, Normalizer.Form.NFKC);
        if (stripped.equals(normalized)) {
            return;
        }
        for (char c : "/?#@:".toCharArray()) {
            if (normalized.indexOf(c) >= 0) {
                throw new InvalidUrlException(
                        "netloc '" + netloc + "' contains invalid characters under NFKC normalization");
            }
        }
    }

    /**
     * CPython {@code urllib.parse._check_bracketed_netloc}.
     *
     * <p>Enforces bracket <em>placement</em>, which is separate from validating the address itself:
     * nothing may precede {@code [}, and anything after {@code ]} must be the {@code :port}
     * delimiter. Without this, {@code https://[::1]a} is silently accepted.
     *
     * <p>The split here must mirror {@code _hostinfo}: userinfo is removed with the <em>last</em>
     * {@code @}, and a {@code [} located before that {@code @} is userinfo text, not a host bracket.
     */
    private static void checkBracketedNetloc(String netloc) {
        int at = netloc.lastIndexOf('@');
        String hostnameAndPort = at >= 0 ? netloc.substring(at + 1) : netloc;
        int open = hostnameAndPort.indexOf('[');
        String hostname;
        if (open >= 0) {
            if (open > 0) {
                throw new InvalidUrlException("Invalid IPv6 URL");
            }
            String bracketed = hostnameAndPort.substring(open + 1);
            int close = bracketed.indexOf(']');
            hostname = close >= 0 ? bracketed.substring(0, close) : bracketed;
            String port = close >= 0 ? bracketed.substring(close + 1) : "";
            if (!port.isEmpty() && !port.startsWith(":")) {
                throw new InvalidUrlException("Invalid IPv6 URL");
            }
        } else {
            int colon = hostnameAndPort.indexOf(':');
            hostname = colon >= 0 ? hostnameAndPort.substring(0, colon) : hostnameAndPort;
        }
        checkBracketedHost(hostname);
    }

    /**
     * CPython {@code urllib.parse._check_bracketed_host}.
     *
     * <p>CPython delegates to {@code ipaddress.ip_address}, so the IPv4 branch must be an actual
     * IPv4 parse: a bare {@code [abc]} is "does not appear to be an IPv4 or IPv6 address", not the
     * IPv4-in-brackets error.
     */
    private static void checkBracketedHost(String hostname) {
        if (hostname.startsWith("v") || hostname.startsWith("V")) {
            if (!IPV_FUTURE.matcher(hostname).matches()) {
                throw new InvalidUrlException("IPvFuture address is invalid");
            }
            return;
        }
        if (isIpv4(hostname)) {
            throw new InvalidUrlException("An IPv4 address cannot be in brackets");
        }
        if (!isIpv6(hostname)) {
            throw new InvalidUrlException("'" + hostname + "' does not appear to be an IPv4 or IPv6 address");
        }
    }

    private static boolean isIpv6(String value) {
        String address = value;
        int percent = address.indexOf('%');
        if (percent >= 0) {
            address = address.substring(0, percent);
        }
        String[] halves = address.split("::", -1);
        if (halves.length > 2) {
            return false;
        }
        int groups = 0;
        for (int half = 0; half < halves.length; half++) {
            if (halves[half].isEmpty()) {
                continue;
            }
            for (String piece : halves[half].split(":", -1)) {
                if (piece.isEmpty()) {
                    return false;
                }
                if (piece.indexOf('.') >= 0) {
                    if (half != halves.length - 1 || !isIpv4(piece)) {
                        return false;
                    }
                    groups += 2;
                    continue;
                }
                if (piece.length() > 4) {
                    return false;
                }
                for (int i = 0; i < piece.length(); i++) {
                    if (Character.digit(piece.charAt(i), 16) < 0) {
                        return false;
                    }
                }
                groups++;
            }
        }
        return halves.length == 2 ? groups < 8 : groups == 8;
    }

    private static boolean isIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !isAsciiDigits(octet)) {
                return false;
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    /** CPython strips leading C0 control characters and spaces ({@code U+0000}..{@code U+0020}). */
    private static String lstripC0ControlOrSpace(String value) {
        int start = 0;
        while (start < value.length() && value.charAt(start) <= 0x20) {
            start++;
        }
        return value.substring(start);
    }

    private static boolean isAsciiAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** Matches CPython {@code str.isdigit() and str.isascii()} as used by the {@code port} property. */
    private static boolean isAsciiDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Reproduces Python's {@code repr()} for the simple strings that reach the port diagnostic. */
    private static String pythonRepr(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
