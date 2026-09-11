#!/usr/bin/env bash
#
# Differential verification: runs the original Python validator and the Java port
# over the same inputs and asserts that stdout, stderr and the exit code agree.
#
# Usage: bin/verify-migration.sh /path/to/python/awesome-artificial-intelligence
#
set -uo pipefail

PY_REPO="${1:-}"
if [ -z "$PY_REPO" ] || [ ! -f "$PY_REPO/scripts/validate_readme.py" ]; then
  echo "usage: $0 <path-to-python-repo>" >&2
  exit 64
fi

JAVA_REPO="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$JAVA_REPO/target/awesome-artificial-intelligence-0.1.0.jar"
if [ ! -f "$JAR" ]; then
  echo "jar not built; run: mvn -B -DskipTests package" >&2
  exit 64
fi

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0

# Compare the two implementations on a single README file.
compare() {
  local label="$1" file="$2"

  ( cd "$PY_REPO" && python3 scripts/validate_readme.py "$file" ) \
    >"$WORK/py.out" 2>"$WORK/py.err"
  local py_code=$?

  ( cd "$JAVA_REPO" && "$JAVA_BIN" -jar "$JAR" "$file" ) \
    >"$WORK/java.out" 2>"$WORK/java.err"
  local java_code=$?

  if [ "$py_code" != "$java_code" ]; then
    echo "FAIL [$label] exit code: python=$py_code java=$java_code"
    fail=$((fail + 1))
    return
  fi
  if ! diff -q "$WORK/py.out" "$WORK/java.out" >/dev/null; then
    echo "FAIL [$label] stdout differs:"
    diff "$WORK/py.out" "$WORK/java.out" | sed 's/^/    /'
    fail=$((fail + 1))
    return
  fi
  if ! diff -q "$WORK/py.err" "$WORK/java.err" >/dev/null; then
    echo "FAIL [$label] stderr differs:"
    diff "$WORK/py.err" "$WORK/java.err" | sed 's/^/    /'
    fail=$((fail + 1))
    return
  fi
  echo "ok   [$label] exit=$py_code $(head -1 "$WORK/py.out")"
  pass=$((pass + 1))
}

# Write a fixture into both repos under the same relative path, then compare.
fixture() {
  local name="$1" body="$2"
  local rel="__fixture_${name}.md"
  printf '%s' "$body" >"$PY_REPO/$rel"
  printf '%s' "$body" >"$JAVA_REPO/$rel"
  compare "$name" "$rel"
  rm -f "$PY_REPO/$rel" "$JAVA_REPO/$rel"
}

echo "== real content files =="
compare "README.md" "README.md"
compare "archive/README.md" "archive/README.md"
compare "CONTRIBUTING.md" "CONTRIBUTING.md"
compare "CURATION.md" "CURATION.md"
compare "AUTOMATION.md" "AUTOMATION.md"

echo
echo "== fixtures =="
fixture "valid" '# List

### Books

- [A Book](https://example.com/book): A useful book.
'
fixture "malformed-http" '### Books

- [A Book](http://example.com): No TLS.
'
fixture "missing-period" '### Books

- [A Book](https://example.com/book): Missing punctuation
'
fixture "orphan-resource" '# List

### Books

- [A Book](https://example.com/book): A useful book.

## Contributing

- [A Tool](https://example.com/tool): A tool.
'
fixture "empty-category" '### Books

Some prose.
'
fixture "duplicate-title-and-url" '### Books

- [A Book](https://EXAMPLE.com/book/): First entry.
- [a book](https://example.com/book#section): Second entry.
'
fixture "invalid-port" '### Books

- [A Book](https://example.com:bad/book): Invalid port.
'
fixture "same-category-two-sections" '## First

### Tools

## Second

### Tools

- [A Tool](https://example.com/tool): A tool.
'
fixture "everything-at-once" '## Learn

### Empty

### Books

- [A Book](https://example.com/book): A useful book.
- not a resource line
- [Bad](http://example.com): Insecure.
- [A BOOK](https://example.com/book/): Duplicate.
- [Fine](https://example.com/other): No period here
'
fixture "repeated-heading" '## Learn

### Books

- [A Book](https://example.com/book): A useful book.

### Books

'
fixture "port-8443-and-trailing-slashes" '### Books

- [One](https://example.com:8443/a/b/): First.
- [Two](https://EXAMPLE.com:8443/a/b): Second.
'
fixture "empty" ''

echo
echo "== churn =="
# Churn is compared at library level: the --base path only adds a `git show` fetch,
# which is exercised separately by the CLI cases below.
cat >"$WORK/ChurnProbe.java" <<'EOF'
import com.awesomeai.validator.ChurnValidator;

public class ChurnProbe {
    public static void main(String[] args) {
        ChurnValidator.validateChurn(args[0], args[1]).forEach(System.out::println);
    }
}
EOF
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -cp "$JAR" -d "$WORK" "$WORK/ChurnProbe.java" || exit 70

churn_case() {
  local label="$1" base_text="$2" current_text="$3"
  local py java
  py=$(cd "$PY_REPO" && python3 -c "
import sys
from scripts.validate_readme import validate_churn
for line in validate_churn(sys.argv[1], sys.argv[2]):
    print(line)
" "$base_text" "$current_text")
  java=$("$JAVA_BIN" -cp "$JAR:$WORK" ChurnProbe "$base_text" "$current_text")
  if [ "$py" == "$java" ]; then
    echo "ok   [churn:$label]"
    pass=$((pass + 1))
  else
    echo "FAIL [churn:$label]"
    echo "    python: $py"
    echo "    java:   $java"
    fail=$((fail + 1))
  fi
}

BASE_CHURN='## Learn

### Books

- [Book](https://example.com/book): A book.

## Build

### Tools

- [Tool 0](https://example.com/0): A tool.
- [Tool 1](https://example.com/1): A tool.
- [Tool 2](https://example.com/2): A tool.
- [Tool 3](https://example.com/3): A tool.
- [Tool 4](https://example.com/4): A tool.
- [Tool 5](https://example.com/5): A tool.'

churn_case "identical" "$BASE_CHURN" "$BASE_CHURN"
churn_case "six-changes" "$BASE_CHURN" "${BASE_CHURN//A tool./A better tool.}"
churn_case "seven-changes" "$BASE_CHURN" "${BASE_CHURN//A tool./A better tool.}
- [Tool 7](https://example.com/7): A tool."
churn_case "four-additions" "$BASE_CHURN" "$BASE_CHURN
- [New 0](https://example.com/new-0): A tool.
- [New 1](https://example.com/new-1): A tool.
- [New 2](https://example.com/new-2): A tool.
- [New 3](https://example.com/new-3): A tool."
churn_case "structurally-invalid" "### Books

Prose." "$BASE_CHURN"

echo
echo "== cli surface =="
cli_case() {
  local label="$1"; shift
  ( cd "$PY_REPO" && python3 scripts/validate_readme.py "$@" ) >"$WORK/py.out" 2>"$WORK/py.err"
  local py_code=$?
  ( cd "$JAVA_REPO" && "$JAVA_BIN" -jar "$JAR" "$@" ) >"$WORK/java.out" 2>"$WORK/java.err"
  local java_code=$?
  if [ "$py_code" == "$java_code" ]; then
    echo "ok   [cli:$label] exit=$py_code"
    pass=$((pass + 1))
  else
    echo "FAIL [cli:$label] python=$py_code java=$java_code"
    fail=$((fail + 1))
  fi
}
cli_case "no-args"
cli_case "bad-flag" --nope
cli_case "base-missing-value" --base
cli_case "extra-positional" README.md README.md
cli_case "help" --help

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
