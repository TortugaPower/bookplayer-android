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

# An empty listing is not "nothing changed", it is "we were not told" — a failed or truncated read. The 3000 is
# the GitHub API's own cap on a pull request's file listing, past which the answer is silently partial.
if [ "$count" -eq 0 ] || [ "$count" -ge 3000 ]; then
  echo "changed-file listing is unusable ($count entries); building" >&2
  echo "android=true"
  exit 0
fi

[ "$android" = true ] || echo "$count changed file(s), none of them Android: skipping Gradle" >&2
echo "android=$android"
