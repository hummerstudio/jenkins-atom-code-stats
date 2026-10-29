#!/usr/bin/env bash
# Counts must be exact: three files, known lines, one block comment spanning two lines.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

dir="$(mktemp -d)"; mkdir -p "$dir/src" "$dir/target"
cat >"$dir/src/A.java" <<'JAVA'
package demo;

// a line comment
public class A {
    /*
     * block
     */
    int x = 1;
}
JAVA
printf 'def f():\n    # comment\n    return 1\n\n' >"$dir/src/b.py"
printf 'x = 1\n' >"$dir/src/c.py"
printf 'public class Gen { }\n' >"$dir/target/Gen.java"
printf '{"label": "Code statistics"}' >"$dir/input.json"

run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" files)" = "3" ] || fail "a file under target/ must not be counted: $(cat "$dir/output.json")"
[ "$(output "$dir" code)" = "7" ] || fail "code lines: $(cat "$dir/output.json")"
[ "$(output "$dir" comments)" = "5" ] || fail "comment lines: $(cat "$dir/output.json")"
[ "$(output "$dir" blanks)" = "2" ] || fail "blank lines: $(cat "$dir/output.json")"
[ -f "$dir/out/code-stats/index.html" ] || fail "no report"
grep -q "Python" "$dir/out/code-stats/index.html" || fail "report misses a language"

# a budget on the file count trips the atom (0 means "no budget", as everywhere else)
dir="$(mktemp -d)"; mkdir -p "$dir"
printf 'x = 1\n' >"$dir/one.py"
printf 'y = 2\n' >"$dir/two.py"
printf '{"maxFiles": "1"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "two files over a budget of one must fail"
grep -q "the limit is 1" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

# nothing to count is a clear error
dir="$(mktemp -d)"
printf '{}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "an empty workspace must fail"
grep -q "no source file matched" "$dir/output.json" || fail "message not readable"

echo "smoke ok: files/code/comment/blank counts, exclusion, budget, empty workspace"
