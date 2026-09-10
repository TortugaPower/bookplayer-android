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
};

// This file is not in its own corpus: its allowlist KEYS are identifiers, so scanning it would let every entry
// justify itself — `planClosures` is "in the code" the moment it is written down here.
const SELF = 'comments.test.mjs';
const sourceFiles = () =>
  ['review.mjs', 'github.mjs', ...readdirSync(`${DIR}test`).filter((f) => f.endsWith('.mjs') && f !== SELF).map((f) => `test/${f}`)];

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
  const code = sources.map((s) => s.replace(/\/\/.*$/gm, '')).join('\n');

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

test('the allowlist is a list of decisions, not a drawer', () => {
  // An entry that stops being needed has to go, or the list becomes the place names go to be forgotten — which
  // is the failure this file is about, one level up.
  const files = sourceFiles();
  const sources = files.map((f) => readFileSync(`${DIR}${f}`, 'utf8'));
  const named = new Set(sources.flatMap((s) => [...identifiersInComments(s)]));
  const code = sources.map((s) => s.replace(/\/\/.*$/gm, '')).join('\n');

  for (const [name, reason] of Object.entries(NOT_CODE)) {
    assert.ok(reason.length > 20, `${name}: an allowlist entry needs a reason worth reading`);
    assert.ok(named.has(name), `${name} is allowlisted but no comment names it any more — delete the entry`);
    assert.equal(new RegExp(`\\b${name}\\b`).test(code), false, `${name} is allowlisted as "not code" but the code has it now — delete the entry`);
  }
});
