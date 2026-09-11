package com.awesomeai.validator;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validate the structure and links in the curated README.
 *
 * <p>CLI port of {@code main()} in {@code scripts/validate_readme.py}.
 */
public final class ValidateReadme {

    private ValidateReadme() {
    }

    /** PARITY: the only intentional CLI rename; the Python original reports {@code validate_readme.py}. */
    private static final String PROG = "validate-readme";

    private static final String USAGE =
            "usage: " + PROG + " [-h] [--check-links] [--base BASE] [readme]";

    private static final String HELP = USAGE + "\n"
            + "\n"
            + "positional arguments:\n"
            + "  readme\n"
            + "\n"
            + "options:\n"
            + "  -h, --help     show this help message and exit\n"
            + "  --check-links\n"
            + "  --base BASE    Git revision used to enforce weekly churn limits\n";

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        int status = run(args, out, err);
        out.flush();
        err.flush();
        System.exit(status);
    }

    /** Parsed command line, mirroring the argparse namespace. */
    record Arguments(Path readme, boolean checkLinks, String base) {
    }

    /** Signals an argparse-style failure, which exits with status 2. */
    private static final class UsageError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageError(String message) {
            super(message);
        }
    }

    /** Signals {@code -h}/{@code --help}, which exits with status 0. */
    private static final class HelpRequested extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Arguments arguments;
        try {
            arguments = parseArguments(args);
        } catch (HelpRequested help) {
            out.print(HELP);
            return 0;
        } catch (UsageError error) {
            err.print(USAGE + "\n");
            err.print(PROG + ": error: " + error.getMessage() + "\n");
            return 2;
        }

        String currentText;
        try {
            currentText = Files.readString(arguments.readme(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            err.print(PROG + ": error: cannot read " + arguments.readme() + ": " + error.getMessage() + "\n");
            return 1;
        }

        ReadmeParser.ValidationResult result = ReadmeParser.validateText(currentText);
        List<Resource> resources = result.resources();
        List<String> errors = new ArrayList<>(result.errors());
        List<String> warnings = new ArrayList<>(result.warnings());

        if (arguments.checkLinks()) {
            LinkChecker.LinkResults links = LinkChecker.checkLinks(resources);
            errors.addAll(links.errors());
            warnings.addAll(links.warnings());
        }

        if (arguments.base() != null) {
            String baseText;
            try {
                baseText = gitShow(arguments.base(), arguments.readme());
            } catch (IOException | InterruptedException error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                err.print(error.getMessage() + "\n");
                return 1;
            }
            errors.addAll(ChurnValidator.validateChurn(baseText, currentText));
        }

        for (String warning : warnings) {
            err.print("WARNING: " + warning + "\n");
        }
        for (String error : errors) {
            err.print("ERROR: " + error + "\n");
        }
        out.print("Validated " + resources.size() + " resources with " + errors.size()
                + " errors and " + warnings.size() + " warnings.\n");
        return errors.isEmpty() ? 0 : 1;
    }

    /**
     * Reads the README at a git revision.
     *
     * <p>Equivalent to {@code subprocess.run(..., check=True)}: a non-zero exit aborts the run.
     */
    private static String gitShow(String base, Path readme) throws IOException, InterruptedException {
        // Path.as_posix(): forward slashes regardless of platform.
        String posix = readme.toString().replace(java.io.File.separatorChar, '/');
        List<String> command = List.of("git", "show", base + ":" + posix);
        ProcessBuilder builder = new ProcessBuilder(command);
        Process process = builder.start();
        byte[] stdout = process.getInputStream().readAllBytes();
        process.getErrorStream().readAllBytes();
        int status = process.waitFor();
        if (status != 0) {
            throw new IOException("CalledProcessError: Command '" + PythonText.reprList(command)
                    + "' returned non-zero exit status " + status + ".");
        }
        return new String(stdout, StandardCharsets.UTF_8);
    }

    /**
     * Minimal argparse-compatible parser for this command's three arguments.
     *
     * <p>Supports {@code --opt value}, {@code --opt=value}, unambiguous long-option abbreviation
     * and the {@code --} separator, matching argparse defaults.
     */
    static Arguments parseArguments(String[] args) {
        Map<String, Boolean> options = new LinkedHashMap<>();
        options.put("--help", false);
        options.put("--check-links", false);
        options.put("--base", true);

        Path readme = null;
        boolean checkLinks = false;
        String base = null;
        List<String> unrecognized = new ArrayList<>();
        boolean positionalOnly = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if (positionalOnly || arg.equals("-")) {
                if (readme == null) {
                    readme = Path.of(arg);
                } else {
                    unrecognized.add(arg);
                }
                continue;
            }
            if (arg.equals("--")) {
                positionalOnly = true;
                continue;
            }
            if (arg.equals("-h")) {
                throw new HelpRequested();
            }
            if (arg.startsWith("--")) {
                String name = arg;
                String inlineValue = null;
                int equals = arg.indexOf('=');
                if (equals >= 0) {
                    name = arg.substring(0, equals);
                    inlineValue = arg.substring(equals + 1);
                }

                List<String> matches = new ArrayList<>();
                for (String candidate : options.keySet()) {
                    if (candidate.equals(name)) {
                        matches.clear();
                        matches.add(candidate);
                        break;
                    }
                    if (candidate.startsWith(name)) {
                        matches.add(candidate);
                    }
                }
                if (matches.isEmpty()) {
                    unrecognized.add(arg);
                    continue;
                }
                if (matches.size() > 1) {
                    throw new UsageError("ambiguous option: " + name + " could match "
                            + String.join(", ", matches));
                }

                String resolved = matches.get(0);
                switch (resolved) {
                    case "--help" -> throw new HelpRequested();
                    case "--check-links" -> {
                        if (inlineValue != null) {
                            throw new UsageError("argument --check-links: ignored explicit argument '"
                                    + inlineValue + "'");
                        }
                        checkLinks = true;
                    }
                    case "--base" -> {
                        if (inlineValue != null) {
                            base = inlineValue;
                        } else if (i + 1 < args.length) {
                            base = args[++i];
                        } else {
                            throw new UsageError("argument --base: expected one argument");
                        }
                    }
                    default -> unrecognized.add(arg);
                }
                continue;
            }
            if (arg.startsWith("-") && arg.length() > 1) {
                unrecognized.add(arg);
                continue;
            }
            if (readme == null) {
                readme = Path.of(arg);
            } else {
                unrecognized.add(arg);
            }
        }

        if (!unrecognized.isEmpty()) {
            throw new UsageError("unrecognized arguments: " + String.join(" ", unrecognized));
        }
        return new Arguments(readme == null ? Path.of("README.md") : readme, checkLinks, base);
    }
}
