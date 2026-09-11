#!/usr/bin/env bash
# Does this change need the Android build?
#
# Reads a list of changed paths on stdin, one per line, and prints `android=true` or `android=false` on stdout in
# the form GitHub Actions wants for $GITHUB_OUTPUT. Reasons go to stderr, where the run log picks them up.
#
# It lives in a file rather than inline in ci.yml because it decides whether a REQUIRED check does any work: a
# wrong `false` means an untested Kotlin change gets a green `build`. Logic that can do that has to be testable,
# and YAML is where the last three arithmetic bugs in this repo's workflows hid. See
# `.github/claude/reviewer/test/ci-scope.test.mjs`, which runs this script against the cases below.
#
# The fail-direction is fixed: anything this cannot answer builds. Building something that did not need it costs
# twelve minutes; skipping something that did costs a green check on untested code.
set -euo pipefail

count=0
android=false
# `|| [ -n "$path" ]`: a `while read` loop drops a final line that has no trailing newline, so a one-line listing
# arriving unterminated would count ZERO paths — which this script reads as "we were not told" and answers with a
# build. Safe, but wrong for the wrong reason, and it hid until the tests fed it exactly that shape.
while IFS= read -r path || [ -n "$path" ]; do
  [ -n "$path" ] || continue
  count=$((count + 1))
  case "$path" in
    # LOCATION BEATS EXTENSION. A module's source tree is compiled and packaged wholesale — a Markdown file under
    # `app/src/main/assets/` ships in the APK and goes through AAPT — so "it is a .md" is not a reason to skip the
    # build when the path says otherwise. This case comes first for that reason.
    app/*|core/*|wear/*) android=true; echo "needs the Android build: $path" >&2 ;;
    # Inert for the Android build: the reviewer harness and its own workflow, documentation, gitignore.
    # Everything else builds — Gradle files, Kotlin, resources, manifests, scripts/, and ci.yml itself, which
    # defines the build and so can never be assumed harmless to it.
    .github/claude/*|.github/workflows/claude-review.yml|*.md|.gitignore) ;;
    *)
      android=true
      echo "needs the Android build: $path" >&2
      ;;
  esac
done

# An empty listing is not "nothing changed", it is "we were not told" — a failed or truncated read.
#
# The 3000 is GitHub's cap on a pull request's FILE listing, and what is counted here is LINES: the caller emits a
# rename's old path as well as its new one, so 3000 lines can be as few as 1500 files. That makes this a
# conservative proxy — it can declare a fully-listed change set unusable and build anyway — and it is deliberately
# not corrected to 6000, because a listing of 3000 files with no renames is exactly 3000 lines and IS truncated.
# Counting lines over-builds; counting against a doubled bound would skip a truncated listing, and that is the one
# direction this script may never fall.
if [ "$count" -eq 0 ] || [ "$count" -ge 3000 ]; then
  echo "changed-file listing is unusable ($count entries); building" >&2
  echo "android=true"
  exit 0
fi

[ "$android" = true ] || echo "$count changed file(s), none of them Android: skipping Gradle" >&2
echo "android=$android"
