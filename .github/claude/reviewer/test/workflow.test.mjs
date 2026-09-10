// The workflow is part of the harness, and until now it was the part with no tests.
//
// Three consecutive review rounds found bugs in `claude-review.yml`, and all three were the same kind: arithmetic
// nobody could check. A hang burning the job's clock because the steps were unbounded; a step cap that turned out
// to be tighter than the harness's own budget, so the step was killed mid-write; step caps that summed to more
// than the job cap, so the job timeout could still be the binding one. Each was caught by a careful reader doing
// sums in their head, and each defeats the guarantee the rest of this harness is organised around — because a job
// cancelled by ITS OWN timeout runs no `if: failure()` step at all, so the note saying the reviewer did not run
// never fires: a red check, and nothing on the pull request.
//
// So the sums live here now. The reader below is deliberately strict rather than a YAML parser: it accepts only
// the shapes this file actually uses and throws on anything else, for the same reason `analyzeShell` does — a
// parser that guesses is a parser that agrees with you about a file you have misread.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const WORKFLOW = fileURLToPath(new URL('../../../workflows/claude-review.yml', import.meta.url));
const HARNESS = fileURLToPath(new URL('../review.mjs', import.meta.url));

// What the harness itself budgets, read from its source rather than restated here: the whole point is that two
// files stop disagreeing.
function harnessDefaultMinutes(name) {
  const src = readFileSync(HARNESS, 'utf8');
  const m = new RegExp(`const ${name} = num\\(process\\.env\\.\\w+, (\\d+) \\* 60 \\* 1000\\)`).exec(src);
  assert.ok(m, `could not find ${name}'s default in review.mjs — this test is reading the wrong shape`);
  return Number(m[1]);
}

function readWorkflow() {
  const lines = readFileSync(WORKFLOW, 'utf8').split('\n');
  const steps = [];
  let jobTimeout = null;
  let inSteps = false;
  let current = null;
  for (const [i, line] of lines.entries()) {
    if (/^\s*#/.test(line) || !line.trim()) continue;
    // Job-level keys sit at four spaces; `steps:` opens the sequence and nothing at that indent follows it here.
    if (/^ {4}timeout-minutes: \d+$/.test(line) && !inSteps) {
      jobTimeout = Number(line.trim().split(': ')[1]);
      continue;
    }
    if (/^ {4}steps:$/.test(line)) {
      inSteps = true;
      continue;
    }
    if (!inSteps) continue;
    const stepStart = /^ {6}- (\w[\w-]*): (.*)$/.exec(line);
    if (stepStart) {
      current = { line: i + 1 };
      steps.push(current);
      current[stepStart[1]] = stepStart[2];
      continue;
    }
    const key = /^ {8}(\w[\w-]*):(.*)$/.exec(line);
    if (key) {
      assert.ok(current, `${WORKFLOW}:${i + 1}: a step key before any step — the reader has lost the shape`);
      current[key[1]] = key[2].trim();
      continue;
    }
    // Deeper lines belong to a `with:`/`env:` block, and a multi-line `if: >-` continues at any depth. Neither
    // changes an answer here, but an unindented line inside `steps:` means the file is not the shape assumed.
    assert.ok(/^ {10,}/.test(line) || /^ {6,}[^-]/.test(line), `${WORKFLOW}:${i + 1}: unrecognised line inside steps: ${line}`);
  }
  assert.ok(jobTimeout, 'no job-level timeout-minutes found');
  assert.ok(steps.length >= 5, `only ${steps.length} steps parsed — the reader is not seeing the file`);
  return { jobTimeout, steps };
}

// A step's cap, with the inline comment that usually follows it. Strict on purpose: a value this cannot parse is
// an error, not a zero — the sums below are the whole point, and `Number('6   # ...')` is NaN, which compares
// false against every bound and would have made this file pass by saying nothing.
const minutes = (step) => {
  const raw = String(step['timeout-minutes'] ?? '');
  const m = /^(\d+)\s*(?:#.*)?$/.exec(raw);
  assert.ok(m, `${step.name || step.uses}: could not read a timeout from ${JSON.stringify(raw)}`);
  return Number(m[1]);
};

const named = (steps, fragment) => steps.filter((s) => (s.name || s.uses || '').includes(fragment));
const only = (steps, fragment) => {
  const found = named(steps, fragment);
  assert.equal(found.length, 1, `expected exactly one step matching ${JSON.stringify(fragment)}, found ${found.length}`);
  return found[0];
};

test('every step in the review job is bounded', () => {
  const { steps } = readWorkflow();
  const uncapped = steps.filter((s) => !s.hasOwnProperty('timeout-minutes')).map((s) => s.name || s.uses);
  assert.deepEqual(uncapped, [], 'a step with no timeout can burn the job cap, and a job cancelled by its own cap posts nothing');
});

test('the step caps fit inside the job cap with slack', () => {
  const { jobTimeout, steps } = readWorkflow();
  // The two notes are mutually exclusive (asserted below), so only the longer of them can ever run.
  const notes = named(steps, 'Say on the PR');
  const others = steps.filter((s) => !notes.includes(s));
  const sum = others.reduce((n, s) => n + minutes(s), 0) + Math.max(...notes.map(minutes));
  // Slack is for what the caps do not cover: per-step startup, the cache restore, the runner's own bookkeeping.
  // At 38 the sum was 37 and the worst path landed on 38:00 exactly, which is how this test came to exist.
  assert.ok(sum + 4 <= jobTimeout, `step caps sum to ${sum} against a job cap of ${jobTimeout}: the job timeout can bind, and it posts nothing when it does`);
});

test("the review step's cap is looser than the harness's own budget", () => {
  const { jobTimeout, steps } = readWorkflow();
  const review = only(steps, 'Run Claude review');
  const cap = minutes(review);
  const budget = harnessDefaultMinutes('JOB_BUDGET_MS');
  // review.mjs measures its budget from before the model lookup, and the reconcile phase that follows it is
  // deliberately unclocked (up to MAX_INLINE posts plus a resolve and a reply per closed thread). If this cap is
  // the tighter of the two, the step is killed mid-write — and a killed step explains nothing on the PR.
  assert.ok(cap >= budget + 4, `the review step's ${cap} min leaves ${cap - budget} for a reconcile the harness does not clock; the harness budgets ${budget}`);
  assert.ok(cap < jobTimeout, 'the review step must fail on its own cap before the job is cancelled on the job cap');
});

test('the two failure notes cover the failures the harness cannot report itself', () => {
  const { steps } = readWorkflow();
  const setupNote = only(steps, 'harness did not run');
  const killedNote = only(steps, 'review step was killed');

  for (const note of [setupNote, killedNote]) {
    // `always()` and `cancelled()` would also fire when a newer push cancels this run through
    // `concurrency: cancel-in-progress`, posting "the reviewer did not run" on a PR whose review is already
    // running again. `failure()` is what keeps these notes about failures.
    assert.match(note.if, /^failure\(\)/, `${note.name}: must be gated on failure()`);
    assert.equal(/always\(\)|cancelled\(\)/.test(note.if), false, `${note.name}: would fire on a superseded run`);
  }
  // Exclusive: exactly one of them can run, which is what lets the cap arithmetic count one.
  assert.match(setupNote.if, /steps\.review\.outcome != 'failure'/);
  assert.match(killedNote.if, /steps\.review\.outcome == 'failure'/);
  // And the killed-note must not overwrite an explanation review.mjs already posted: they share a heading, so
  // the second write replaces the first and would trade the real error for a generic one.
  assert.match(killedNote.if, /steps\.review\.outputs\.explained != 'true'/);
  assert.match(readFileSync(HARNESS, 'utf8'), /appendFileSync\(out, 'explained=true/, 'nothing in the harness writes the output that gate reads');
});

test("the harness's own tests run before the review", () => {
  const { steps } = readWorkflow();
  const tests = only(steps, "tool allowlist");
  const review = only(steps, 'Run Claude review');
  assert.ok(tests.line < review.line, 'a red suite must stop the review, not follow it');
});

test('the budget numbers written in prose are the real ones', () => {
  // The drift this catches has happened twice: a cap moved and the comments explaining it did not, so the only
  // place the arithmetic is written down said 25 while the file said 38 — and a maintainer reasoning from a
  // comment gets the wrong bound. The convention is the point: when prose names a cap, it writes it as "the
  // job's N" or "the review step's N", and this test reads both files and checks every one of them.
  const { jobTimeout, steps } = readWorkflow();
  const reviewCap = minutes(only(steps, 'Run Claude review'));
  const sources = [readFileSync(WORKFLOW, 'utf8'), readFileSync(HARNESS, 'utf8')];
  const claims = { "the job's": jobTimeout, "the review step's": reviewCap };

  let checked = 0;
  for (const src of sources) {
    for (const [phrase, expected] of Object.entries(claims)) {
      for (const m of src.matchAll(new RegExp(`${phrase.replace("'", "['’]")} (\\d+)`, 'g'))) {
        checked++;
        assert.equal(Number(m[1]), expected, `a comment says "${phrase} ${m[1]}" but it is ${expected}`);
      }
    }
  }
  assert.ok(checked >= 3, `only ${checked} prose figures found — the convention has been written around, so this test is no longer reading anything`);
});
