package com.awesomeai.validator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural validation of the curated README.
 *
 * <p>Port of {@code validate_text} in {@code scripts/validate_readme.py}.
 */
public final class ReadmeParser {

    private ReadmeParser() {
    }

    /**
     * Compiled with {@link Pattern#UNICODE_CHARACTER_CLASS} because Python's {@code \s} is
     * Unicode-aware by default on {@code str} patterns while Java's is ASCII-only.
     *
     * <p>The leading {@code ^} and trailing {@code $} of the Python pattern are dropped in favour
     * of {@link Matcher#matches()}, which is equivalent for already-split lines.
     *
     * <p>Note the hardcoded {@code https://}: a plain {@code http://} entry is reported as
     * <em>malformed</em>, not as an insecure-scheme warning.
     */
    private static final Pattern RESOURCE_RE =
            Pattern.compile("- \\[([^\\]]+)]\\((https://[^)\\s]+)\\): (.+)", Pattern.UNICODE_CHARACTER_CLASS);

    /** Python's {@code LINK_RE = re.compile(r"^- \[")} used only as a prefix test. */
    private static final String LINK_PREFIX = "- [";

    /** A category is identified by its section as well as its own name. */
    private record CategoryKey(String section, String category) {
    }

    /** The 3-tuple returned by Python's {@code validate_text}. */
    public record ValidationResult(List<Resource> resources, List<String> errors, List<String> warnings) {
    }

    /**
     * Parses and validates README text.
     *
     * <p>The returned warning list is always empty; the shape is preserved from the original.
     */
    public static ValidationResult validateText(String text) {
        List<Resource> resources = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String section = "";
        String category = "";
        // LinkedHashMap is required: the "has no resources" diagnostics are emitted in insertion
        // order, and a HashMap would scramble them.
        Map<CategoryKey, Integer> categoryLines = new LinkedHashMap<>();
        Map<CategoryKey, Integer> categoryCounts = new LinkedHashMap<>();

        List<String> lines = PythonText.splitLines(text);
        for (int index = 0; index < lines.size(); index++) {
            int lineNumber = index + 1;
            String line = lines.get(index);

            if (line.startsWith("## ")) {
                section = PythonText.strip(line.substring(3));
                category = "";
                continue;
            }
            if (line.startsWith("### ")) {
                category = PythonText.strip(line.substring(4));
                CategoryKey key = new CategoryKey(section, category);
                // A repeated heading updates the recorded line and resets the counter, exactly as
                // the Python dict assignments do.
                categoryLines.put(key, lineNumber);
                categoryCounts.put(key, 0);
                continue;
            }
            if (!line.startsWith(LINK_PREFIX)) {
                continue;
            }

            Matcher match = RESOURCE_RE.matcher(line);
            if (!match.matches()) {
                errors.add("line " + lineNumber + ": malformed resource entry");
                continue;
            }
            if (category.isEmpty()) {
                errors.add("line " + lineNumber + ": resource is outside a level-three category");
                continue;
            }

            String title = match.group(1);
            String url = match.group(2);
            String description = match.group(3);
            if (!description.endsWith(".")) {
                // Deliberately no `continue`: the resource is still recorded.
                errors.add("line " + lineNumber + ": description must end with a period");
            }
            resources.add(new Resource(
                    lineNumber, section, category, PythonText.strip(title), url, PythonText.strip(description)));
            CategoryKey key = new CategoryKey(section, category);
            categoryCounts.merge(key, 1, Integer::sum);
        }

        for (Map.Entry<CategoryKey, Integer> entry : categoryCounts.entrySet()) {
            if (entry.getValue() != 0) {
                continue;
            }
            CategoryKey key = entry.getKey();
            String location = key.section().isEmpty() ? "" : " in section '" + key.section() + "'";
            errors.add("line " + categoryLines.get(key) + ": category '" + key.category() + "'" + location
                    + " has no resources");
        }

        Map<String, Resource> seenTitles = new LinkedHashMap<>();
        Map<String, Resource> seenUrls = new LinkedHashMap<>();
        for (Resource resource : resources) {
            String titleKey = PythonText.casefold(resource.title());
            Resource priorTitle = seenTitles.get(titleKey);
            if (priorTitle != null) {
                errors.add("line " + resource.line() + ": duplicate title '" + resource.title()
                        + "' (first used on line " + priorTitle.line() + ")");
            } else {
                seenTitles.put(titleKey, resource);
            }

            String urlKey;
            try {
                urlKey = UrlNormalizer.normalize(resource.url());
            } catch (InvalidUrlException error) {
                errors.add("line " + resource.line() + ": invalid URL '" + resource.url() + "' ("
                        + error.getMessage() + ")");
                continue;
            }
            Resource priorUrl = seenUrls.get(urlKey);
            if (priorUrl != null) {
                errors.add("line " + resource.line() + ": duplicate URL '" + resource.url()
                        + "' (first used on line " + priorUrl.line() + ")");
            } else {
                seenUrls.put(urlKey, resource);
            }
        }

        return new ValidationResult(resources, errors, warnings);
    }
}
