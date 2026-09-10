// `scripts/ci-scope.sh` decides whether CI runs the Android build, and `build` is a REQUIRED check on main and
// develop — so a wrong `false` hands a green required check to untested Kotlin. That is worth more than a shape
// assertion on a YAML string, which is what testing it inline in `ci.yml` would have amounted to.
//
// It lives in this suite because this is the repo's only script-level test runner, and this suite runs on every
// pull request (the reviewer workflow is not gated on paths — it reviews everything).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const SCRIPT = fileURLToPath(new URL('../../../../scripts/ci-scope.sh', import.meta.url));

const decide = (paths) => {
  // Deliberately UNTERMINATED (no trailing newline): that is the shape a `while read` loop silently drops, and
  // the workflow's `printf '%s\n'` hides it. The script has to count the last path either way.
  const out = execFileSync('bash', [SCRIPT], { input: Array.isArray(paths) ? paths.join('\n') : paths, encoding: 'utf8' });
  const m = /^android=(true|false)$/m.exec(out.trim());
  assert.ok(m, `the script printed something unusable: ${JSON.stringify(out)}`);
  return m[1] === 'true';
};

test('a change to anything the Android build compiles, builds', () => {
  for (const path of [
    'app/src/main/java/com/tortugapower/audiobookplayer/ui/LibraryScreen.kt',
    'core/src/main/java/com/tortugapower/audiobookplayer/logic/PlaybackManager.kt',
    'wear/src/main/java/com/tortugapower/audiobookplayer/wear/WearApp.kt',
    'app/build.gradle.kts',
    'gradle/libs.versions.toml',
    'app/src/main/res/values/strings.xml',
    'app/src/main/AndroidManifest.xml',
    'app/proguard-rules.pro',
    'scripts/audit-mapping.sh',
    'gradle.properties',
    // The build's own definition. It can change what the build does, so it can never be assumed harmless to it —
    // which is why this pull request, which edits ci.yml, still builds.
    '.github/workflows/ci.yml',
    // A path shape nothing anticipated is not a reason to skip.
    'app/src/main/res/raw/a file with spaces.mp3',
    'src/androidTest/java/Weird$Name.kt',
  ]) {
    assert.equal(decide([path]), true, `${path} should build`);
  }
});

test('a change the Android build cannot see, does not build it', () => {
  assert.equal(decide(['.github/claude/reviewer/review.mjs']), false);
  assert.equal(decide(['.github/claude/review-guide.md']), false);
  assert.equal(decide(['.github/workflows/claude-review.yml']), false);
  assert.equal(decide(['README.md', 'CLAUDE.md', 'docs/media-servers-testing.md']), false);
  assert.equal(decide(['.gitignore']), false);
  // The whole reviewer suite plus its docs: still nothing for Gradle to do.
  assert.equal(decide([
    '.github/claude/reviewer/review.mjs',
    '.github/claude/reviewer/test/round.test.mjs',
    '.github/claude/reviewer/README.md',
    '.github/workflows/claude-review.yml',
    'CLAUDE.md',
  ]), false);
});

test('one Android file among many inert ones still builds', () => {
  assert.equal(decide([
    '.github/claude/reviewer/review.mjs',
    'CLAUDE.md',
    'core/src/main/java/com/tortugapower/audiobookplayer/repository/LibraryRepository.kt',
    '.gitignore',
  ]), true, 'a Kotlin change hidden in a documentation PR must still build');
});

test('a listing this cannot trust builds rather than skipping', () => {
  // Empty is not "nothing changed", it is "we were not told" — a failed or truncated read. And 3000 is the
  // GitHub API's own cap on a pull request's file listing, past which what comes back is silently partial.
  assert.equal(decide(''), true, 'an empty listing must not read as "nothing to build"');
  assert.equal(decide('\n\n\n'), true, 'blank lines are not a listing either');
  const huge = Array.from({ length: 3000 }, (_, i) => `.github/claude/reviewer/file${i}.md`);
  assert.equal(decide(huge), true, 'a listing at the API cap is partial, and a partial listing decides nothing');
  assert.equal(decide(huge.slice(0, 2999)), false, 'just under the cap is still a complete answer');
});

test('a listing that ends without a newline still counts its last path', () => {
  // Both spellings of the same list must agree. They did not: `while read` returns non-zero on the unterminated
  // final line and the loop exits without processing it, so a one-path listing counted zero — and zero means
  // "unusable", which builds. The answer was right and the reason was wrong, which is the kind of thing that is
  // right until the day it is load-bearing.
  assert.equal(decide('core/src/main/java/Player.kt'), true);
  assert.equal(decide('core/src/main/java/Player.kt\n'), true);
  assert.equal(decide('.github/claude/reviewer/review.mjs'), false);
  assert.equal(decide('.github/claude/reviewer/review.mjs\n'), false);
});

test('the decision is on stdout and the reasoning is not', () => {
  // The step appends stdout straight to $GITHUB_OUTPUT: anything else printed there becomes a step output, and
  // `android=...` has to be the only line GitHub sees. The explanation belongs in the log, on stderr.
  const out = execFileSync('bash', [SCRIPT], { input: 'app/A.kt\nCLAUDE.md\n', encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
  assert.equal(out.trim(), 'android=true', `stdout carried more than the decision: ${JSON.stringify(out)}`);
});
