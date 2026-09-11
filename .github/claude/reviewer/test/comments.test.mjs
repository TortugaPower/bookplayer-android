// A comment that names something the code does not have.
//
// This harness is heavily commented on purpose — the reasoning is the part that is expensive to reconstruct — and
// that makes a wrong comment expensive too: it is the entry point a maintainer reads before touching the code. The
// review loop has now found five of them, three in the last three rounds: a row saying "answered" when nobody had
// answered, a record field advertising a lookup it never did, two budget figures left behind when a cap moved, a
// duplicated block still describing the previous behaviour, and `See \`disambiguate\`` pointing at a function that
// does not exist under any name.
//
// The last one is the sharpest form and the only one a machine can see cheaply: a comment naming an identifier
// that is nowhere in the code. So it is checked here. The bar is deliberately low — one regex over backticked
// words — and the point is the ALLOWLIST below: when you write `foo` in a comment and `foo` is not in the code,
// you must either fix the name or write down why it is not code. "The function is called something else now" is
// not a reason anyone would write, which is exactly how the check earns its place.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const DIR = fileURLToPath(new URL('..', import.meta.url));

// Named in a comment, deliberately not code. Each one needs a reason, and the reason is the review.
const NOT_CODE = {
  // Code that USED to exist, named so the history is legible.
  planClosures: 'deleted: the resemblance-based closer, named where its removal is explained',
  // Code that USED to exist, named so the history is legible.
  verifiedIds: 'deleted: a guard a mutation sweep proved redundant, named where the seam it left is explained',
  eligibleIds: 'deleted alongside verifiedIds; the test comment explains what the sweep showed',
  resolvedBy: 'a field from an earlier marker design, named where the current rule is contrasted with it',
  // Names owned by something other than this codebase.
  direction: "a GitHub REST query parameter, on an endpoint that ignores it — that is the point of the sentence",
  pushd: 'a shell builtin the tool gate refuses, named in the list of what it refuses',
  realpath: 'the POSIX call, named where the harness explains what it resolves paths with',
  onStop: 'an Android lifecycle method, named in the example of two findings that differ by one word',
};

// Calls named in comments that belong to somebody else's vocabulary.
const NOT_OURS = {
  'Number': 'the JavaScript builtin',
  'always': "a GitHub Actions expression function, named where the workflow's conditions are explained",
  'cancelled': 'a GitHub Actions expression function, named for the same reason',
  'failure': 'a GitHub Actions expression function, named for the same reason',
};

// This file is not in its own corpus: its allowlist KEYS are identifiers, so scanning it would let every entry
// justify itself — `planClosures` is "in the code" the moment it is written down here.
const SELF = 'comments.test.mjs';
const sourceFiles = () =>
  ['review.mjs', 'github.mjs', ...readdirSync(`${DIR}test`).filter((f) => f.endsWith('.mjs') && f !== SELF).map((f) => `test/${f}`)];

// The code a comment in this directory may legitimately name is not only JavaScript: these tests reason about
// the harness's own workflow, and `concurrency` or `timeout-minutes` are as real as any function here. It is part
// of the corpus a name resolves against, with its own comment syntax stripped.
const NEIGHBOURS = [
  ['../../../workflows/claude-review.yml', /^\s*#.*$/gm],
];
const neighbourCode = () =>
  NEIGHBOURS.map(([rel, comments]) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8').replace(comments, '')).join('\n');

const identifiersInComments = (text) => {
  const found = new Set();
  for (const line of text.split('\n')) {
    const comment = /^\s*(?:\/\/|#|\*)(.*)$/.exec(line);
    if (!comment) continue;
    for (const token of comment[1].matchAll(/`([^`]+)`/g)) {
      // Only things shaped like an identifier: no dots, slashes, spaces or punctuation, and long enough that a
      // word like `id` or `fp` does not drag prose into this.
      if (/^[A-Za-z_][A-Za-z0-9_]{3,}$/.test(token[1])) found.add(token[1]);
    }
  }
  return found;
};

test('every identifier a comment names exists in the code', () => {
  const files = sourceFiles();
  const sources = files.map((f) => readFileSync(`${DIR}${f}`, 'utf8'));
  // Comments stripped: a name that appears ONLY in comments is exactly what this is looking for, and one comment
  // agreeing with another is not evidence of anything.
  const code = [...sources.map((s) => s.replace(/\/\/.*$/gm, '')), neighbourCode()].join('\n');

  const unresolved = [];
  for (const [file, text] of files.map((f, i) => [f, sources[i]])) {
    for (const name of identifiersInComments(text)) {
      if (NOT_CODE[name]) continue;
      if (new RegExp(`\\b${name}\\b`).test(code)) continue;
      unresolved.push(`${file}: \`${name}\` is named in a comment and is nowhere in the code`);
    }
  }
  assert.deepEqual(unresolved, [], `${unresolved.length} comment(s) name something that does not exist:\n${unresolved.join('\n')}`);
});

test('a comment that names a CALL names a function that exists', () => {
  // The sharper half, and the one that would have caught `main()` — which survived the plain-identifier check for
  // twelve comments because "main" also exists in the code as the string `BASE_REF || 'main'` and as a branch name
  // in both workflows. A backticked `name()` is a claim about a FUNCTION, so it is checked against declarations
  // rather than against any occurrence of the word.
  const files = sourceFiles();
  const sources = files.map((f) => readFileSync(`${DIR}${f}`, 'utf8'));
  const code = sources.map((s) => s.replace(/\/\/.*$/gm, '')).join('\n');
  const declared = new Set([
    ...[...code.matchAll(/\b(?:export\s+)?(?:async\s+)?function\s+(\w+)/g)].map((m) => m[1]),
    ...[...code.matchAll(/\b(?:const|let|var)\s+(\w+)\s*=/g)].map((m) => m[1]),
  ]);

  const unresolved = [];
  for (const [file, text] of files.map((f, i) => [f, sources[i]])) {
    for (const line of text.split('\n')) {
      const comment = /^\s*(?:\/\/|#|\*)(.*)$/.exec(line);
      if (!comment) continue;
      for (const call of comment[1].matchAll(/`(\w+)\(\)`/g)) {
        if (NOT_OURS[call[1]] || declared.has(call[1])) continue;
        unresolved.push(`${file}: \`${call[1]}()\` is named in a comment and no function by that name is declared`);
      }
    }
  }
  assert.deepEqual(unresolved, [], `${unresolved.length} comment(s) name a function that does not exist:\n${unresolved.join('\n')}`);
});

test('the allowlist is a list of decisions, not a drawer', () => {
  // An entry that stops being needed has to go, or the list becomes the place names go to be forgotten — which
  // is the failure this file is about, one level up.
  const files = sourceFiles();
  const sources = files.map((f) => readFileSync(`${DIR}${f}`, 'utf8'));
  const named = new Set(sources.flatMap((s) => [...identifiersInComments(s)]));
  const code = [...sources.map((s) => s.replace(/\/\/.*$/gm, '')), neighbourCode()].join('\n');

  for (const [name, reason] of Object.entries(NOT_CODE)) {
    assert.ok(reason.length > 20, `${name}: an allowlist entry needs a reason worth reading`);
    assert.ok(named.has(name), `${name} is allowlisted but no comment names it any more — delete the entry`);
    assert.equal(new RegExp(`\\b${name}\\b`).test(code), false, `${name} is allowlisted as "not code" but the code has it now — delete the entry`);
  }
});

test('nothing reaches the log with an upstream message still in it', () => {
  // The rule this file's subject states about itself: "every string that leaves this process goes through
  // `redact`, log lines included". It was applied by hand — twice, by regex — and both times the regex was the
  // boundary rather than the rule: the first sweep matched `${e.message}` and missed `${msg}`, the second missed
  // a `reason` whose own third branch embedded an error. A public repository's run log is public, and `rest()`
  // deliberately embeds the whole upstream response body in its error messages.
  //
  // So it is checked, with no exemption for "this one is already safe": `redact` is idempotent, so wrapping a
  // value that was built from redacted parts costs nothing, and a rule with exemptions is the thing that let two
  // sweeps miss three sites. Anything interpolated into a console call whose NAME says it carries an error is
  // wrapped at the interpolation, full stop.
  const src = readFileSync(`${DIR}review.mjs`, 'utf8');
  const carriesError = /\b(message|msg|stack|reason)\b/i;
  const offenders = [];
  for (const [i, line] of src.split('\n').entries()) {
    if (!/console\.(warn|log|error)\(/.test(line)) continue;
    for (const m of line.matchAll(/\$\{([A-Za-z_$][\w$]*(?:\.\w+)*)\}/g)) {
      // `m[1]`, plainly: a RegExp match has `groups` (named captures), never a `group()` method, so the ternary
      // that used to be here had a dead branch — in the file whose whole subject is claims that are not true.
      const expr = m[1];
      if (!carriesError.test(expr)) continue;
      if (line.includes(`redact(${expr})`)) continue;
      offenders.push(`review.mjs:${i + 1}: \${${expr}} reaches the log unredacted — ${line.trim().slice(0, 80)}`);
    }
  }
  assert.deepEqual(offenders, [], `wrap these in redact():\n${offenders.join('\n')}`);
});

test("model-authored text reaches the log only through boundedDump", () => {
  // `boundedDump` is the one wrapper that does all three things this needs: it redacts, it bounds, and it breaks
  // a leading `::` so model text cannot forge a workflow command. The redaction check above cannot see this
  // class — it keys on names like `message` and `reason`, and `f.same_as` is neither — and a public run log is
  // where an unbounded finding, or a `same_as` filled with prose quoted from the diff, would land verbatim.
  //
  // The DRY_RUN print goes through it too, and loses nothing: the default bound is thousands of characters, far
  // past any real finding, and redaction only touches secret shapes.
  const src = readFileSync(`${DIR}review.mjs`, 'utf8');
  // Keyed on the FIELD, not the object it hangs off. `[fvd].file` was the first spelling and
  // `claimedThread.path` was the second — the same text under another variable — so the object name proved to be
  // the wrong half to match on. A GitHub-derived path caught by this loses nothing: `boundedDump` is idempotent
  // on short strings.
  const modelText = /\.(file|comment|same_as|evidence|text|summary|path)\b/;
  // A console call SPANS LINES in this file, and the first version of this check required the `console.` and the
  // interpolation to be on one — which is how the second site in `keyFindings` stayed unbounded while the first
  // was fixed and this test passed. Depth is tracked across lines, and the tracker errs toward staying inside a
  // call (more lines checked, never fewer).
  const offenders = [];
  let depth = 0;
  for (const [i, line] of src.split('\n').entries()) {
    const opens = (line.match(/\(/g) || []).length;
    const closes = (line.match(/\)/g) || []).length;
    const starts = /console\.(warn|log|error)\(/.test(line);
    if (!starts && depth <= 0) continue;
    if (starts && depth <= 0) depth = opens - closes;
    else depth += opens - closes;
    for (const m of line.matchAll(/\$\{([^}]*)\}/g)) {
      const expr = m[1];
      if (!modelText.test(expr)) continue;
      if (/boundedDump\(/.test(expr)) continue;
      offenders.push(`review.mjs:${i + 1}: \${${expr}} — model text to the log without boundedDump`);
    }
  }
  assert.deepEqual(offenders, [], `wrap these in boundedDump():\n${offenders.join('\n')}`);
});
