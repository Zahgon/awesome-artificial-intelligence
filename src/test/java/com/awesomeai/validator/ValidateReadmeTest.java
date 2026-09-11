package com.awesomeai.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port of {@code tests/test_validate_readme.py}.
 *
 * <p>The first twelve tests mirror the Python suite one-for-one, including names. The remainder
 * are additional parity guards that lock down the CPython behaviours the JDK does not share.
 */
class ValidateReadmeTest {

    private static final String VALID = """
            # List

            ### Books

            - [A Book](https://example.com/book): A useful book.
            """;

    private static boolean anyContains(List<String> values, String needle) {
        return values.stream().anyMatch(value -> value.contains(needle));
    }

    // ---------------------------------------------------------------------
    // Ported from tests/test_validate_readme.py
    // ---------------------------------------------------------------------

    @Test
    void testValidResource() {
        ReadmeParser.ValidationResult result = ReadmeParser.validateText(VALID);
        assertEquals(1, result.resources().size());
        assertEquals(List.of(), result.errors());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void testMalformedResource() {
        ReadmeParser.ValidationResult result =
                ReadmeParser.validateText("### Books\n\n- [A Book](http://example.com): No TLS.\n");
        assertTrue(result.errors().get(0).contains("malformed resource entry"));
    }

    @Test
    void testDuplicateTitleAndNormalizedUrl() {
        String text = """
                ### Books

                - [A Book](https://EXAMPLE.com/book/): First entry.
                - [a book](https://example.com/book#section): Second entry.
                """;
        List<String> errors = ReadmeParser.validateText(text).errors();
        assertTrue(anyContains(errors, "duplicate title"));
        assertTrue(anyContains(errors, "duplicate URL"));
    }

    @Test
    void testEmptyCategory() {
        List<String> errors = ReadmeParser.validateText("### Books\n\nSome prose.\n").errors();
        assertTrue(errors.get(0).contains("category 'Books' has no resources"));
    }

    @Test
    void testLevelTwoHeadingResetsCategory() {
        String text = VALID + "\n## Contributing\n\n- [A Tool](https://example.com/tool): A tool.\n";
        List<String> errors = ReadmeParser.validateText(text).errors();
        assertTrue(anyContains(errors, "outside a level-three category"));
    }

    @Test
    void testSameCategoryNameInDifferentSections() {
        String text = """
                ## First

                ### Tools

                ## Second

                ### Tools

                - [A Tool](https://example.com/tool): A tool.
                """;
        List<String> errors = ReadmeParser.validateText(text).errors();
        assertTrue(anyContains(errors, "section 'First'"));
    }

    @Test
    void testDescriptionNeedsPeriod() {
        List<String> errors = ReadmeParser.validateText(
                "### Books\n\n- [A Book](https://example.com/book): Missing punctuation\n").errors();
        assertTrue(errors.get(0).contains("description must end with a period"));
    }

    @Test
    void testNormalizeUrl() {
        assertEquals(
                "https://example.com/path?q=1",
                UrlNormalizer.normalize("HTTPS://EXAMPLE.COM:443/path/?q=1#fragment"));
    }

    @Test
    void testInvalidUrlIsAnError() {
        List<String> errors = ReadmeParser.validateText(
                "### Books\n\n- [A Book](https://example.com:bad/book): Invalid port.\n").errors();
        assertTrue(errors.get(0).contains("invalid URL"));
    }

    @Test
    void testLinkStatusClassification() {
        assertEquals(Severity.ERROR, LinkChecker.classifyStatus(404, "https://example.com").severity());
        assertEquals(Severity.ERROR, LinkChecker.classifyStatus(400, "https://example.com").severity());
        assertEquals(Severity.ERROR, LinkChecker.classifyStatus(451, "https://example.com").severity());
        assertEquals(Severity.WARNING, LinkChecker.classifyStatus(403, "https://example.com").severity());
        assertEquals(Severity.WARNING, LinkChecker.classifyStatus(408, "https://example.com").severity());
        assertEquals(Severity.WARNING, LinkChecker.classifyStatus(503, "https://example.com").severity());
        assertNull(LinkChecker.classifyStatus(200, "https://example.com"));
    }

    @Test
    void testLinkExceptionClassification() {
        String url = "https://example.invalid";
        Throwable dnsError = new UnknownHostException("not found");
        Throwable tlsError = new SSLException("bad certificate");
        Throwable timeout = new HttpTimeoutException("timed out");
        assertEquals(Severity.ERROR, LinkChecker.classifyException(dnsError, url).severity());
        assertEquals(Severity.ERROR, LinkChecker.classifyException(tlsError, url).severity());
        assertEquals(Severity.WARNING, LinkChecker.classifyException(timeout, url).severity());
    }

    @Test
    void testChurnLimits() {
        String base = "## Learn\n\n### Books\n\n- [Book](https://example.com/book): A book.\n\n"
                + "## Build\n\n### Tools\n\n"
                + IntStream.range(0, 6)
                        .mapToObj(index -> "- [Tool " + index + "](https://example.com/" + index + "): A tool.")
                        .collect(Collectors.joining("\n"));

        // Python's str.replace takes a replacement count; String.replace does not.
        String acceptable = PythonText.replace(base, "A tool.", "A better tool.", 6);
        assertEquals(List.of(), ChurnValidator.validateChurn(base, acceptable));

        String tooMany = acceptable + "\n- [Tool 7](https://example.com/7): A tool.\n";
        assertTrue(anyContains(ChurnValidator.validateChurn(base, tooMany), "resource entries"));

        String fourAdditions = base + "\n" + IntStream.range(0, 4)
                .mapToObj(index -> "- [New " + index + "](https://example.com/new-" + index + "): A tool.")
                .collect(Collectors.joining("\n"));
        assertTrue(anyContains(ChurnValidator.validateChurn(base, fourAdditions), "net entries"));

        String twoFoundations = base.replace("A book.", "A revised book.")
                .replace("\n## Build", "\n- [Second Book](https://example.com/book-2): A book.\n\n## Build");
        assertTrue(anyContains(ChurnValidator.validateChurn(base, twoFoundations), "foundational entries"));

        String movedToFoundations = base.replace("A book.", "A revised book.")
                .replace(
                        "\n## Build\n\n### Tools\n\n- [Tool 0](https://example.com/0): A tool.",
                        "\n- [Tool 0](https://example.com/0): A tool.\n\n## Build\n\n### Tools");
        assertTrue(anyContains(ChurnValidator.validateChurn(base, movedToFoundations), "foundational entries"));
    }

    // ---------------------------------------------------------------------
    // Additional parity guards (not present in the Python suite)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("normalizeUrl matches CPython on every reference vector")
    void testNormalizeUrlReferenceVectors() {
        assertEquals("https://example.com/path?q=1",
                UrlNormalizer.normalize("HTTPS://EXAMPLE.COM:443/path/?q=1#fragment"));
        assertEquals("https://example.com/", UrlNormalizer.normalize("https://example.com"));
        assertEquals("https://example.com:8443/a/b",
                UrlNormalizer.normalize("https://example.com:8443/a/b/"));
        assertEquals("https://ex.com/", UrlNormalizer.normalize("https://ex.com/?"));
        assertEquals("https://ex.com/a?x=1&y=2", UrlNormalizer.normalize("https://ex.com/a?x=1&y=2#f"));
        InvalidUrlException error = assertThrows(InvalidUrlException.class,
                () -> UrlNormalizer.normalize("https://example.com:bad/book"));
        assertEquals("Port could not be cast to integer value as 'bad'", error.getMessage());
    }

    @Test
    @DisplayName("normalizeUrl drops userinfo and keeps a non-default port")
    void testNormalizeUrlAuthority() {
        assertEquals("https://example.com/x", UrlNormalizer.normalize("https://user:pw@EXAMPLE.com/x/"));
        assertEquals("https://example.com:8443/", UrlNormalizer.normalize("https://example.com:8443"));
        assertEquals("https://example.com/a/b", UrlNormalizer.normalize("https://example.com/a/b///"));
    }

    @Test
    @DisplayName("the checked-in README validates exactly as it does under Python")
    void testRealReadmeIsClean() throws IOException {
        Path readme = Path.of("README.md");
        String text = Files.readString(readme, StandardCharsets.UTF_8);
        ReadmeParser.ValidationResult result = ReadmeParser.validateText(text);
        assertEquals(List.of(), result.errors());
        assertEquals(List.of(), result.warnings());
        assertEquals(77, result.resources().size());
    }

    @Test
    @DisplayName("errors are emitted in CPython's order: per-line, then empty categories, then duplicates")
    void testErrorOrdering() {
        String text = """
                ## Learn

                ### Empty

                ### Books

                - [A Book](https://example.com/book): A useful book.
                - not a resource line
                - [Bad](http://example.com): Insecure.
                - [A BOOK](https://example.com/book/): Duplicate.
                - [Fine](https://example.com/other): No period here
                """;
        List<String> errors = ReadmeParser.validateText(text).errors();
        assertEquals(List.of(
                "line 9: malformed resource entry",
                "line 11: description must end with a period",
                "line 3: category 'Empty' in section 'Learn' has no resources",
                "line 10: duplicate title 'A BOOK' (first used on line 7)",
                "line 10: duplicate URL 'https://example.com/book/' (first used on line 7)"),
                errors);
    }

    @Test
    @DisplayName("a resource outside a level-three category is not recorded")
    void testOrphanResourceIsNotRecorded() {
        ReadmeParser.ValidationResult result = ReadmeParser.validateText(
                "## Section\n\n- [A Tool](https://example.com/tool): A tool.\n");
        assertEquals(0, result.resources().size());
        assertEquals(1, result.errors().size());
    }

    @Test
    @DisplayName("an entry missing its period is still recorded as a resource")
    void testMissingPeriodStillRecordsResource() {
        ReadmeParser.ValidationResult result = ReadmeParser.validateText(
                "### Books\n\n- [A Book](https://example.com/book): No period\n");
        assertEquals(1, result.resources().size());
        assertEquals(1, result.errors().size());
    }

    @Test
    @DisplayName("a repeated category heading resets the entry counter")
    void testRepeatedCategoryHeadingResetsCount() {
        String text = """
                ## Learn

                ### Books

                - [A Book](https://example.com/book): A useful book.

                ### Books

                """;
        List<String> errors = ReadmeParser.validateText(text).errors();
        assertEquals(List.of("line 7: category 'Books' in section 'Learn' has no resources"), errors);
    }

    @Test
    @DisplayName("churn reports a structural failure before computing limits")
    void testChurnRequiresValidInput() {
        assertEquals(
                List.of("cannot calculate churn until both README versions are structurally valid"),
                ChurnValidator.validateChurn("### Books\n\nProse.\n", VALID));
    }

    @Test
    @DisplayName("splitLines follows CPython boundaries, not String.lines()")
    void testPythonSplitLines() {
        assertEquals(List.of("a", "b"), PythonText.splitLines("a\u2028b"));
        assertEquals(List.of("a", "b"), PythonText.splitLines("a\u000Bb"));
        assertEquals(List.of("a", "b"), PythonText.splitLines("a\r\nb"));
        assertEquals(List.of("a"), PythonText.splitLines("a\n"));
        assertEquals(List.of(), PythonText.splitLines(""));
        // U+001F is Python whitespace but is NOT a line boundary.
        assertEquals(List.of("a\u001Fb"), PythonText.splitLines("a\u001Fb"));
    }

    @Test
    @DisplayName("strip covers the non-breaking spaces Python treats as whitespace")
    void testPythonStrip() {
        assertEquals("a", PythonText.strip("\u00A0 a \u00A0"));
        assertEquals("a", PythonText.strip("\u2007a\u202F"));
        assertEquals("a", PythonText.strip("  a\t\n"));
    }

    @Test
    @DisplayName("casefold folds the cases plain toLowerCase gets wrong")
    void testCasefold() {
        assertEquals("ss", PythonText.casefold("\u00DF"));
        assertEquals("fi", PythonText.casefold("\uFB01"));
        assertEquals("\u03C3", PythonText.casefold("\u03C2"));
        assertEquals("a book", PythonText.casefold("A BOOK"));
    }

    @Test
    @DisplayName("casefold matches CPython where lower(upper(c)) does not")
    void testCasefoldExceptions() {
        assertEquals("strasse", PythonText.casefold("STRA\u1E9EE"));
        assertEquals("titan\u0131c", PythonText.casefold("titan\u0131c"));
        assertNotEquals("titanic", PythonText.casefold("titan\u0131c"));
        assertEquals("\u13A0", PythonText.casefold("\u13A0"));
        assertEquals("\u13A0", PythonText.casefold("\uAB70"));
        assertEquals("\u13F0", PythonText.casefold("\u13F8"));
        assertEquals("\uA7CE", PythonText.casefold("\uA7CE"));
        assertEquals("\uD81B\uDEA0", PythonText.casefold("\uD81B\uDEA0"));
    }

    @Test
    @DisplayName("casefold is context-free, unlike toLowerCase's final-sigma rule")
    void testCasefoldIgnoresFinalSigmaContext() {
        assertEquals("\u03B1\u03C3", PythonText.casefold("\u0391\u03A3"));
        assertEquals("\u03B1\u03C3\u03B1", PythonText.casefold("\u0391\u03A3\u0391"));
        assertEquals("\u03C3", PythonText.casefold("\u03A3"));
    }

    @Test
    @DisplayName("non-ASCII titles fold like CPython when detecting duplicates")
    void testDuplicateDetectionUsesRealCasefold() {
        String distinct = """
                ### Books

                - [titan\u0131c](https://example.com/a): First entry.
                - [titanic](https://example.com/b): Second entry.
                """;
        assertTrue(ReadmeParser.validateText(distinct).errors().isEmpty());

        String duplicate = """
                ### Books

                - [STRA\u1E9EE](https://example.com/a): First entry.
                - [strasse](https://example.com/b): Second entry.
                """;
        assertTrue(anyContains(ReadmeParser.validateText(duplicate).errors(), "duplicate title"));
    }

    @Test
    @DisplayName("urlunsplit keeps CPython's empty-authority marker")
    void testNormalizeEmptyAuthority() {
        assertEquals("https:///path", UrlNormalizer.normalize("https:///path"));
        assertEquals("https:////path", UrlNormalizer.normalize("https:////path"));
        assertEquals("https:///", UrlNormalizer.normalize("https://"));
    }

    @Test
    @DisplayName("bracketed hosts reject stray data and misparsed addresses")
    void testBracketedHostValidation() {
        assertEquals("Invalid IPv6 URL",
                assertThrows(InvalidUrlException.class,
                        () -> UrlNormalizer.normalize("https://[::1]a")).getMessage());
        assertEquals("Invalid IPv6 URL",
                assertThrows(InvalidUrlException.class,
                        () -> UrlNormalizer.normalize("https://a[::1]/")).getMessage());
        assertEquals("'abc' does not appear to be an IPv4 or IPv6 address",
                assertThrows(InvalidUrlException.class,
                        () -> UrlNormalizer.normalize("https://[abc]/")).getMessage());
        assertEquals("An IPv4 address cannot be in brackets",
                assertThrows(InvalidUrlException.class,
                        () -> UrlNormalizer.normalize("https://[1.2.3.4]/")).getMessage());
        assertEquals("https://v1.x/", UrlNormalizer.normalize("https://[V1.x]/"));
        assertEquals("https://::1:8080/", UrlNormalizer.normalize("https://[::1]:8080/"));
    }

    @Test
    @DisplayName("replace honours a replacement count")
    void testCountedReplace() {
        assertEquals("b b a a", PythonText.replace("a a a a", "a", "b", 2));
        assertEquals("b b b b", PythonText.replace("a a a a", "a", "b", 6));
    }

    @Test
    @DisplayName("CLI parsing matches the argparse surface")
    void testArgumentParsing() {
        ValidateReadme.Arguments defaults = ValidateReadme.parseArguments(new String[] {});
        assertEquals(Path.of("README.md"), defaults.readme());
        assertTrue(!defaults.checkLinks());
        assertNull(defaults.base());

        ValidateReadme.Arguments full = ValidateReadme.parseArguments(
                new String[] {"docs/OTHER.md", "--check-links", "--base", "origin/master"});
        assertEquals(Path.of("docs/OTHER.md"), full.readme());
        assertTrue(full.checkLinks());
        assertEquals("origin/master", full.base());

        ValidateReadme.Arguments inline =
                ValidateReadme.parseArguments(new String[] {"--base=HEAD~1"});
        assertEquals("HEAD~1", inline.base());

        // argparse allows unambiguous long-option abbreviation.
        ValidateReadme.Arguments abbreviated = ValidateReadme.parseArguments(new String[] {"--ba", "HEAD"});
        assertEquals("HEAD", abbreviated.base());
    }

    @Test
    @DisplayName("request URIs accept the characters RFC 3986 forbids but urllib sends raw")
    void testRequestUriAcceptsIllegalCharacters() throws Exception {
        assertEquals("https://example.com/a%7Cb", LinkChecker.requestUri("https://example.com/a|b").toASCIIString());
        assertEquals("https://example.com/a%5Eb", LinkChecker.requestUri("https://example.com/a^b").toASCIIString());
        assertEquals("https://example.com/a%7Bb%7D", LinkChecker.requestUri("https://example.com/a{b}").toASCIIString());
        assertEquals("https://example.com/a%5Cb", LinkChecker.requestUri("https://example.com/a\\b").toASCIIString());
        assertEquals("https://example.com/a%60b", LinkChecker.requestUri("https://example.com/a`b").toASCIIString());
        assertEquals("https://example.com/a%22b", LinkChecker.requestUri("https://example.com/a\"b").toASCIIString());
        assertEquals("https://example.com/a%3Cb%3E", LinkChecker.requestUri("https://example.com/a<b>").toASCIIString());
        assertEquals("https://example.com/q?a=1%7C2#f%7Cg",
                LinkChecker.requestUri("https://example.com/q?a=1|2#f|g").toASCIIString());
    }

    @Test
    @DisplayName("request URIs percent-encode non-ASCII paths as UTF-8")
    void testRequestUriEncodesNonAscii() throws Exception {
        assertEquals("https://ja.wikipedia.org/wiki/%E4%BA%BA%E5%B7%A5%E7%9F%A5%E8%83%BD",
                LinkChecker.requestUri("https://ja.wikipedia.org/wiki/\u4EBA\u5DE5\u77E5\u80FD").toASCIIString());
        assertEquals("https://de.wikipedia.org/wiki/K%C3%BCnstliche",
                LinkChecker.requestUri("https://de.wikipedia.org/wiki/K\u00FCnstliche").toASCIIString());
        // Astral code points occupy a surrogate pair and must encode as one four-byte sequence.
        assertEquals("https://example.com/%F0%9F%98%80",
                LinkChecker.requestUri("https://example.com/\uD83D\uDE00").toASCIIString());
    }

    @Test
    @DisplayName("request URIs leave valid URLs and existing escapes untouched")
    void testRequestUriPreservesValidUrls() throws Exception {
        assertEquals("https://example.com/book", LinkChecker.requestUri("https://example.com/book").toASCIIString());
        assertEquals("https://example.com/a%20b?x=1&y=2",
                LinkChecker.requestUri("https://example.com/a%20b?x=1&y=2").toASCIIString());
        assertEquals("https://user:pw@example.com:8443/~a/b.c!d$e&f'g(h)i*j+k,l;m=n",
                LinkChecker.requestUri("https://user:pw@example.com:8443/~a/b.c!d$e&f'g(h)i*j+k,l;m=n")
                        .toASCIIString());
        // A stray percent is not an escape and must itself be escaped for the retry to parse.
        assertEquals("https://example.com/100%25%7Cx",
                LinkChecker.requestUri("https://example.com/100%|x").toASCIIString());
    }

    @Test
    @DisplayName("checkLink surfaces a DNS failure as an unreachable-link error")
    void testCheckLinkUnreachableHost() {
        Resource resource = new Resource(
                1, "Learn", "Books", "A Book", "https://nonexistent.invalid/x", "A book.");
        Finding finding = LinkChecker.checkLink(LinkChecker.newClient(), resource);
        assertNotNull(finding);
        assertEquals(Severity.ERROR, finding.severity());
        assertTrue(finding.message().startsWith("unreachable link: https://nonexistent.invalid/x"));
    }

    private record Invocation(int status, String out, String err) {
    }

    private static Invocation invoke(String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int status;
        try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            status = ValidateReadme.run(args, out, err);
        }
        return new Invocation(
                status,
                outBytes.toString(StandardCharsets.UTF_8),
                errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("--help prints usage to stdout and exits 0")
    void testRunPrintsHelp() {
        Invocation result = invoke("--help");
        assertEquals(0, result.status());
        assertTrue(result.out().startsWith("usage: validate-readme"));
        assertEquals("", result.err());
    }

    @Test
    @DisplayName("an unknown flag exits 2 with an argparse-style message")
    void testRunRejectsUnknownFlag() {
        Invocation result = invoke("README.md", "--nope");
        assertEquals(2, result.status());
        assertEquals("", result.out());
        assertTrue(result.err().contains("unrecognized arguments: --nope"));
    }

    @Test
    @DisplayName("an unreadable README exits 1 without a stack trace")
    void testRunReportsUnreadableFile(@TempDir Path directory) {
        Invocation result = invoke(directory.resolve("absent.md").toString());
        assertEquals(1, result.status());
        assertEquals("", result.out());
        assertTrue(result.err().contains("error: cannot read"));
    }

    @Test
    @DisplayName("a clean README is summarised on stdout and exits 0")
    void testRunValidatesCleanFile(@TempDir Path directory) throws IOException {
        Path readme = directory.resolve("README.md");
        Files.writeString(readme, VALID, StandardCharsets.UTF_8);
        Invocation result = invoke(readme.toString());
        assertEquals(0, result.status());
        assertEquals("Validated 1 resources with 0 errors and 0 warnings.\n", result.out());
        assertEquals("", result.err());
    }

    @Test
    @DisplayName("validation errors go to stderr and exit 1")
    void testRunReportsValidationErrors(@TempDir Path directory) throws IOException {
        Path readme = directory.resolve("README.md");
        Files.writeString(readme, "# List\n\n### Books\n\n- broken entry\n", StandardCharsets.UTF_8);
        Invocation result = invoke(readme.toString());
        assertEquals(1, result.status());
        assertTrue(result.out().startsWith("Validated 0 resources with "));
        assertTrue(result.err().contains("ERROR: "));
    }

    @Test
    @DisplayName("an unresolvable --base revision exits 1 through the git path")
    void testRunReportsMissingBaseRevision(@TempDir Path directory) throws IOException {
        Path readme = directory.resolve("README.md");
        Files.writeString(readme, VALID, StandardCharsets.UTF_8);
        Invocation result = invoke(readme.toString(), "--base", "refs/qc/definitely-absent");
        assertEquals(1, result.status());
        assertNotEquals("", result.err());
    }

    @Test
    @DisplayName("checkLinks fans out and sorts findings by severity")
    void testCheckLinksClassifiesResponses() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int status = switch (exchange.getRequestURI().getPath()) {
                case "/gone" -> 404;
                case "/blocked" -> 403;
                default -> 200;
            };
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            List<Resource> resources = List.of(
                    new Resource(1, "S", "C", "Ok", root + "/ok", "Fine."),
                    new Resource(2, "S", "C", "Gone", root + "/gone", "Missing."),
                    new Resource(3, "S", "C", "Blocked", root + "/blocked", "Blocked."));

            LinkChecker.LinkResults results = LinkChecker.checkLinks(resources);

            assertEquals(List.of("broken link (404): " + root + "/gone"), results.errors());
            assertEquals(List.of("link check blocked (403): " + root + "/blocked"), results.warnings());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("checkLinks on an empty list yields no findings")
    void testCheckLinksWithNoResources() {
        LinkChecker.LinkResults results = LinkChecker.checkLinks(List.of());
        assertEquals(List.of(), results.errors());
        assertEquals(List.of(), results.warnings());
    }

    @Test
    @DisplayName("severity labels match the Python strings")
    void testSeverityLabels() {
        assertEquals("error", Severity.ERROR.label());
        assertEquals("warning", Severity.WARNING.label());
    }

    @Test
    @DisplayName("CODE_POINT_ORDER sorts by code point, not UTF-16 unit")
    void testCodePointOrderComparator() {
        String astral = "\uD83D\uDE00";
        String replacement = "\uFFFD";
        assertTrue(PythonText.CODE_POINT_ORDER.compare(replacement, astral) < 0);
        assertTrue(replacement.compareTo(astral) > 0);
        assertEquals(0, PythonText.CODE_POINT_ORDER.compare("abc", "abc"));
        assertTrue(PythonText.CODE_POINT_ORDER.compare("ab", "abc") < 0);
    }

    @Test
    @DisplayName("repr quotes like CPython repr(str)")
    void testReprQuoting() {
        assertEquals("'git'", PythonText.repr("git"));
        assertEquals("\"it's\"", PythonText.repr("it's"));
        assertEquals("'say \"hi\"'", PythonText.repr("say \"hi\""));
        assertEquals("'\\'both\\' \"quotes\"'", PythonText.repr("'both' \"quotes\""));
        assertEquals("'a\\\\b'", PythonText.repr("a\\b"));
        assertEquals("'a\\nb\\rc\\td'", PythonText.repr("a\nb\rc\td"));
        assertEquals("'\\x00\\x1b\\x7f'", PythonText.repr("\u0000\u001B\u007F"));
        assertEquals("'caf\u00e9'", PythonText.repr("caf\u00e9"));
        assertEquals("''", PythonText.repr(""));
    }

    @Test
    @DisplayName("reprList renders a Python list literal, not List.toString()")
    void testReprList() {
        List<String> command = List.of("git", "show", "main:README.md");
        assertEquals("['git', 'show', 'main:README.md']", PythonText.reprList(command));
        assertNotEquals(command.toString(), PythonText.reprList(command));
        assertEquals("[]", PythonText.reprList(List.of()));
        assertEquals("['one']", PythonText.reprList(List.of("one")));
    }
}
