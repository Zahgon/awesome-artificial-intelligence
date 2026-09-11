package com.awesomeai.validator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * CPython string semantics that differ from the JDK equivalents.
 *
 * <p>Every helper here exists because a naive JDK call would silently diverge from
 * {@code scripts/validate_readme.py}. The character sets were extracted directly from
 * CPython rather than inferred.
 */
public final class PythonText {

    private PythonText() {
    }

    /**
     * Characters that CPython's {@code str.isspace()} reports as whitespace but that are not
     * covered by the Unicode separator categories.
     *
     * <p>Note {@code \u001F} is whitespace to Python but is <em>not</em> a line boundary.
     */
    private static final String EXTRA_SPACE = "\t\n\u000B\f\r\u001C\u001D\u001E\u001F\u0085";

    /**
     * CPython {@code str.splitlines()} boundaries. Deliberately excludes {@code \u001F},
     * and includes the four control/Unicode boundaries that {@link String#lines()} ignores.
     */
    private static final String LINE_BOUNDARIES = "\n\u000B\f\r\u001C\u001D\u001E\u0085\u2028\u2029";

    /**
     * CPython {@code str.isspace()}.
     *
     * <p>Differs from {@link Character#isWhitespace(int)}, which excludes the non-breaking
     * spaces {@code U+00A0}, {@code U+2007} and {@code U+202F} that Python treats as space.
     */
    public static boolean isSpace(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.SPACE_SEPARATOR
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR) {
            return true;
        }
        return codePoint <= 0xFFFF && EXTRA_SPACE.indexOf((char) codePoint) >= 0;
    }

    /**
     * CPython {@code str.strip()} with no argument.
     *
     * <p>{@link String#strip()} uses {@link Character#isWhitespace}, which disagrees with Python
     * on the non-breaking spaces, so the whitespace test is delegated to {@link #isSpace}.
     */
    public static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int cp = value.codePointAt(start);
            if (!isSpace(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = value.codePointBefore(end);
            if (!isSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return value.substring(start, end);
    }

    /** CPython {@code str.rstrip(chars)} for a set of literal characters. */
    public static String rstrip(String value, String chars) {
        int end = value.length();
        while (end > 0 && chars.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * CPython {@code str.splitlines()}.
     *
     * <p>{@link String#lines()} only recognises {@code \n}, {@code \r} and {@code \r\n}; Python
     * additionally splits on {@code \u000B \f \u001C \u001D \u001E \u0085 \u2028 \u2029}.
     * A trailing boundary does not produce a final empty element, and the empty string yields
     * an empty list.
     */
    public static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int index = 0;
        int start = 0;
        int length = text.length();
        while (index < length) {
            char current = text.charAt(index);
            if (LINE_BOUNDARIES.indexOf(current) >= 0) {
                lines.add(text.substring(start, index));
                if (current == '\r' && index + 1 < length && text.charAt(index + 1) == '\n') {
                    index++;
                }
                index++;
                start = index;
            } else {
                index++;
            }
        }
        if (start < length) {
            lines.add(text.substring(start, length));
        }
        return lines;
    }

    /**
     * CPython {@code str.casefold()}: full, context-free Unicode case folding.
     *
     * <p>The JDK has no case-folding API outside ICU, which this dependency-free port cannot use.
     * Upper-then-lower via {@link Locale#ROOT} is the closest built-in but is wrong twice over:
     *
     * <p>1. {@link String#toLowerCase} applies the context-dependent final-sigma rule, so
     * {@code "ΑΣ"} yields {@code "ας"} (U+03C2) where casefold gives {@code "ασ"} (U+03C3). Folding
     * one code point at a time removes that context; a lone {@code Σ} was always correct, so this
     * surfaces only in multi-character strings.
     *
     * <p>2. Exactly 202 code points fold differently from {@code lower(upper(c))}, found by
     * sweeping all 1,112,064 non-surrogate code points against CPython. They reduce to the rules
     * below, dominated by Cherokee — the rare script that case-folds <em>upward</em>.
     *
     * <p>{@link Locale#ROOT} stays mandatory: the default-locale overload corrupts keys under a
     * Turkish locale.
     */
    public static String casefold(String value) {
        StringBuilder folded = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            appendFolded(folded, codePoint);
        }
        return folded.toString();
    }

    /** Appends the {@code casefold} of a single code point, which never depends on its neighbours. */
    private static void appendFolded(StringBuilder out, int codePoint) {
        if (codePoint < 0x80) {
            // ASCII fast path: the entire README is ASCII, and folding here is just A-Z -> a-z.
            out.append((char) (codePoint >= 'A' && codePoint <= 'Z' ? codePoint + 32 : codePoint));
        } else if (foldsToItself(codePoint)) {
            out.appendCodePoint(codePoint);
        } else if (codePoint >= 0x13F8 && codePoint <= 0x13FD) {
            // Cherokee small letters fold back onto U+13F0..U+13F5.
            out.appendCodePoint(codePoint - 0x8);
        } else if (codePoint >= 0xAB70 && codePoint <= 0xABBF) {
            // Cherokee Supplement folds up to Cherokee capitals U+13A0..U+13EF.
            out.appendCodePoint(codePoint - 0x97D0);
        } else if (codePoint == 0x1E9E) {
            out.append("ss"); // LATIN CAPITAL LETTER SHARP S; upper-then-lower stops at U+00DF.
        } else {
            out.append(new String(Character.toChars(codePoint)).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT));
        }
    }

    /** Code points {@code casefold} leaves alone but {@code lower(upper(c))} would change. */
    private static boolean foldsToItself(int codePoint) {
        return codePoint == 0x0131 // DOTLESS I: uppercases to 'I', which would lower back to 'i'.
                || (codePoint >= 0x13A0 && codePoint <= 0x13F5) // Cherokee capitals: already folded.
                || codePoint == 0xA7CE
                || codePoint == 0xA7D2
                || codePoint == 0xA7D4
                || (codePoint >= 0x16EA0 && codePoint <= 0x16EB8); // Medefaidrin.
    }

    /**
     * Orders strings by Unicode code point, matching Python's {@code sorted()}.
     *
     * <p>{@link String#compareTo} orders by UTF-16 code unit, which places supplementary-plane
     * characters before {@code U+E000..U+FFFF} instead of after.
     */
    public static final Comparator<String> CODE_POINT_ORDER = (left, right) -> {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            int a = left.codePointAt(i);
            int b = right.codePointAt(j);
            if (a != b) {
                return Integer.compare(a, b);
            }
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Integer.compare(left.length() - i, right.length() - j);
    };

    /**
     * CPython {@code str.replace(old, new, count)}.
     *
     * <p>{@link String#replace(CharSequence, CharSequence)} has no count limit and always replaces
     * every occurrence.
     */
    public static String replace(String value, String target, String replacement, int count) {
        if (count < 0) {
            return value.replace(target, replacement);
        }
        StringBuilder out = new StringBuilder();
        int index = 0;
        int done = 0;
        while (done < count) {
            int found = value.indexOf(target, index);
            if (found < 0) {
                break;
            }
            out.append(value, index, found).append(replacement);
            index = found + target.length();
            done++;
        }
        out.append(value.substring(index));
        return out.toString();
    }

    /**
     * CPython {@code repr()} of a {@code str}.
     *
     * <p>Python prefers single quotes and switches to double quotes only when the value contains
     * an apostrophe but no double quote. Backslashes, the active quote and the C0 control
     * characters are escaped; printable non-ASCII is emitted literally, as in Python 3.
     */
    public static String repr(String value) {
        char quote = value.indexOf('\'') >= 0 && value.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append(quote);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character == quote) {
                        out.append('\\').append(character);
                    } else if (character < 0x20 || character == 0x7F) {
                        out.append(String.format("\\x%02x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append(quote).toString();
    }

    /**
     * CPython {@code repr()} of a {@code list[str]}.
     *
     * <p>{@link List#toString()} prints bare elements ({@code [git, show]}); Python quotes each
     * one ({@code ['git', 'show']}). Error messages copied from the Python original need the
     * Python form to stay byte-identical.
     */
    public static String reprList(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                out.append(", ");
            }
            out.append(repr(values.get(index)));
        }
        return out.append(']').toString();
    }
}
