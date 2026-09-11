package com.awesomeai.validator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Enforces the weekly churn budget between two README revisions.
 *
 * <p>Port of {@code validate_churn} in {@code scripts/validate_readme.py}.
 */
public final class ChurnValidator {

    private ChurnValidator() {
    }

    private static final int MAX_CHANGED_ENTRIES = 6;
    private static final int MAX_NET_ADDITIONS = 3;
    private static final int MAX_FOUNDATIONAL_CHANGES = 1;

    /** The section whose entries count as "foundational". */
    private static final String FOUNDATIONAL_SECTION = "learn";

    /** Comparable identity of a resource. Note this uses the raw URL, not the normalised one. */
    private record Signature(String section, String category, String url, String description) {
    }

    /**
     * Compares two README revisions and reports churn-limit violations.
     *
     * @return the violations, in the original's emission order; empty when within budget
     */
    public static List<String> validateChurn(String baseText, String currentText) {
        ReadmeParser.ValidationResult base = ReadmeParser.validateText(baseText);
        ReadmeParser.ValidationResult current = ReadmeParser.validateText(currentText);
        if (!base.errors().isEmpty() || !current.errors().isEmpty()) {
            return List.of("cannot calculate churn until both README versions are structurally valid");
        }

        Map<String, Resource> baseByTitle = byTitle(base.resources());
        Map<String, Resource> currentByTitle = byTitle(current.resources());

        Set<String> titles = new HashSet<>(baseByTitle.keySet());
        titles.addAll(currentByTitle.keySet());

        Set<String> changedTitles = new HashSet<>();
        for (String title : titles) {
            if (!Objects.equals(signature(baseByTitle.get(title)), signature(currentByTitle.get(title)))) {
                changedTitles.add(title);
            }
        }

        int foundationalChanges = 0;
        for (String title : changedTitles) {
            // Counts entries moved into or out of the foundational section, not just edits in place.
            if (isFoundational(baseByTitle.get(title)) || isFoundational(currentByTitle.get(title))) {
                foundationalChanges++;
            }
        }

        // Deliberately the list sizes, so duplicate titles are counted.
        int netAdditions = current.resources().size() - base.resources().size();

        List<String> errors = new ArrayList<>();
        if (changedTitles.size() > MAX_CHANGED_ENTRIES) {
            errors.add("churn limit exceeded: " + changedTitles.size()
                    + " resource entries changed (maximum " + MAX_CHANGED_ENTRIES + ")");
        }
        if (netAdditions > MAX_NET_ADDITIONS) {
            errors.add("churn limit exceeded: " + netAdditions
                    + " net entries added (maximum " + MAX_NET_ADDITIONS + ")");
        }
        if (foundationalChanges > MAX_FOUNDATIONAL_CHANGES) {
            errors.add("churn limit exceeded: " + foundationalChanges
                    + " foundational entries changed (maximum " + MAX_FOUNDATIONAL_CHANGES + ")");
        }
        return errors;
    }

    /** Later duplicates overwrite earlier ones, matching Python's dict-comprehension semantics. */
    private static Map<String, Resource> byTitle(List<Resource> resources) {
        Map<String, Resource> map = new LinkedHashMap<>();
        for (Resource resource : resources) {
            map.put(PythonText.casefold(resource.title()), resource);
        }
        return map;
    }

    private static Signature signature(Resource resource) {
        if (resource == null) {
            return null;
        }
        return new Signature(resource.section(), resource.category(), resource.url(), resource.description());
    }

    private static boolean isFoundational(Resource resource) {
        return resource != null
                && resource.section().toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT)
                        .equals(FOUNDATIONAL_SECTION);
    }
}
