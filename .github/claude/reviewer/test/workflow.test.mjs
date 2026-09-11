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
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const WORKFLOW = fileURLToPath(new URL('../../../workflows/claude-review.yml', import.meta.url));
const CI = fileURLToPath(new URL('../../../workflows/ci.yml', import.meta.url));
const README = fileURLToPath(new URL('../README.md', import.meta.url));
const CLIENT = fileURLToPath(new URL('../github.mjs', import.meta.url));
// Everywhere a budget figure can be written down. Adding a file here is the cheap half of keeping these two
// checks honest; the expensive half is remembering that a check over a FILE LIST is only as wide as the list.
const capSources = () => [
  WORKFLOW,
  CI,
  HARNESS,
  CLIENT,
  README,
  ...readdirSync(fileURLToPath(new URL('.', import.meta.url)))
    .filter((f) => f.endsWith('.mjs'))
    .map((f) => fileURLToPath(new URL(f, import.meta.url))),
];
const HARNESS = fileURLToPath(new URL('../review.mjs', import.meta.url));

// What the harness itself budgets, read from its source rather than restated here: the whole point is that two
// files stop disagreeing.
// The default behind an env knob, by the CONSTANT's name (`JOB_BUDGET_MS`) or by the env variable's
// (`REVIEW_JOB_BUDGET_MS`) — the README's table is keyed by the latter and the code by the former.
function budgetMinutes(envName) {
  const src = readFileSync(HARNESS, 'utf8');
  const m = new RegExp(`num\\(process\\.env\\.${envName}, (\\d+) \\* 60 \\* 1000\\)`).exec(src);
  assert.ok(m, `could not find ${envName}'s default in review.mjs — this test is reading the wrong shape`);
  return Number(m[1]);
}

function harnessDefaultMinutes(name) {
  const src = readFileSync(HARNESS, 'utf8');
  const m = new RegExp(`const ${name} = num\\(process\\.env\\.\\w+, (\\d+) \\* 60 \\* 1000\\)`).exec(src);
  assert.ok(m, `could not find ${name}'s default in review.mjs — this test is reading the wrong shape`);
  return Number(m[1]);
}

function readWorkflow(file = WORKFLOW) {
  const lines = readFileSync(file, 'utf8').split('\n');
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
      assert.ok(current, `${file}:${i + 1}: a step key before any step — the reader has lost the shape`);
      current[key[1]] = key[2].trim();
      continue;
    }
    // Deeper lines belong to a `with:`/`env:` block, and a multi-line `if: >-` continues at any depth. Neither
    // changes an answer here, but an unindented line inside `steps:` means the file is not the shape assumed.
    assert.ok(/^ {10,}/.test(line) || /^ {6,}[^-]/.test(line), `${file}:${i + 1}: unrecognised line inside steps: ${line}`);
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
  const killedNote = only(steps, 'failed without explaining itself');

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
  // And its text may not name a cause it cannot know. The gate fires on "the step failed and nothing was
  // written", which is two cases — killed before any handler ran, or a handler whose write was refused — and
  // asserting the first points a maintainer at the wrong knob when it was the second.
  const killedText = readFileSync(WORKFLOW, 'utf8').slice(readFileSync(WORKFLOW, 'utf8').indexOf('failed without explaining itself'));
  const run = killedText.slice(killedText.indexOf('--setup-failed'), killedText.indexOf('\n', killedText.indexOf('--setup-failed')));
  assert.match(run, /either|or/, 'the note asserts one cause when the gate cannot tell two apart');
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
  // Every file that can carry a cap figure, and that includes `github.mjs` and this suite: a comment there said
  // "the job's 48" while neither check read the file, which is the drift these exist for, one file over.
  const sources = capSources().map((f) => readFileSync(f, 'utf8'));
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

test('the harness suite runs where nothing can skip it', () => {
  // The reviewer workflow runs these tests before every review — and its job `if:` skips draft pull requests,
  // forks and Dependabot. `ci-scope.sh` calls `.github/claude/**` inert. Together that meant a pull request
  // touching only the harness got NO tests: Gradle skipped in seconds by the script itself, and the reviewer job
  // never started, so a lockfile bump of the agent SDK could land on a green required check. `build` is the
  // required check, so the suite runs there, and nothing may gate it.
  const ci = readFileSync(CI, 'utf8');
  const { steps } = readWorkflow(CI);
  const suite = steps.filter((s) => (s.run || '').includes('node --test test/') || (s.name || '').includes('Test the reviewer harness'));
  assert.equal(suite.length, 1, 'ci.yml does not run the reviewer suite');
  // "No `if:`" was the wrong assertion, and it was wrong in the direction that hides the bug: a step without a
  // condition is skipped once any EARLIER step in the job has failed, and these run last (so that a registry blip
  // cannot deny a Kotlin-only pull request its Android signal). On a pull request where Gradle failed, the suite
  // guarding the required check would not have run — and the old assertion would have rejected the fix.
  // `always()` is accepted but `!cancelled()` is the right one: a run superseded by `concurrency` should stop.
  assert.match(
    String(suite[0].if ?? ''),
    /!cancelled\(\)|always\(\)/,
    'the harness suite is skipped when an earlier step fails; it needs `if: ${{ !cancelled() }}`',
  );
  assert.match(ci, /node --test test\//, 'the step exists but does not run the tests');
});

test('the job that runs pull-request code says what it may do', () => {
  // Without a `permissions:` block a job inherits the repository default, which may be "read and write" — a write
  // token in a job that runs PR-authored Gradle. And under the read-only default `pull-requests` is `none`, which
  // works here only because this repo is public: the day it is not, the scope step 403s and silently answers
  // "build everything".
  const ci = readFileSync(CI, 'utf8');
  assert.match(ci, /permissions:\s*\n\s+contents: read\s*\n\s+pull-requests: read/, 'ci.yml\'s build job does not state its permissions');
});

test('nothing in the scope step can fail the required check', () => {
  // Both halves: listing the changed files, and running the script that reads them. Either failing must fall into
  // "build", never into a red `build` — a required check going red over a blip blocks a merge that a re-run fixes.
  const ci = readFileSync(CI, 'utf8');
  const scope = ci.slice(ci.indexOf('Decide whether the Android build has to run'), ci.indexOf('- name: Set up JDK 17'));
  assert.match(scope, /if ! files=/, 'the file listing is unguarded');
  assert.match(scope, /if ! printf/, 'the scope script call is unguarded');
  // Both failure branches WRITE the safe answer. Relying on "no output means build" makes that guarantee depend
  // on how seven other steps spell their condition, and one `== 'true'` added later would invert it silently.
  assert.equal((scope.match(/echo "android=true" >> "\$GITHUB_OUTPUT"/g) || []).length, 2, 'a failure branch leaves the safe answer implicit');
  assert.equal(/set -euo pipefail/.test(scope), false, 'set -e here fails the step, and the step is inside a required check');
  // The path the shell guards cannot cover, and the one this test was NAMED for while not checking it: a step
  // killed by its own `timeout-minutes` is marked failed, and `build` is required. `continue-on-error` sends that
  // the same way — the step leaves no output, and every gated step below runs on `!= 'false'`.
  assert.match(scope, /continue-on-error: true/, "the step's own timeout can still red the required check");
  // Both sides of a rename. `.filename` alone describes where a file ended up, so moving `app/Foo.kt` to
  // `docs/foo.md` listed only the inert side and the gate answered "nothing Android changed" about a change that
  // deleted a Kotlin file — a wrong `false`, which is the one direction this gate may never fall.
  // The `--jq` LINE, not the step text: the paragraph above that line explains `previous_filename`, so matching
  // the step as a whole passed with the filter stripped — a test satisfied by its own prose.
  const jq = scope.split('\n').find((l) => l.includes('--jq'));
  assert.ok(jq, 'the scope step no longer lists the changed files');
  assert.match(jq, /previous_filename/, 'a rename out of an Android path can still answer android=false');
  // And nothing in the script is assembled by template expansion: values arrive through `env:`.
  const run = scope.slice(scope.indexOf('run: |'));
  assert.equal(/\$\{\{/.test(run), false, 'a value is interpolated into the script text instead of passed through env');
});

test("the knob table's budgets are the code's budgets", () => {
  // The README is the document a maintainer reads BEFORE changing a budget, which makes it the worst place for a
  // stale number — and the prose check above reads only the workflow and review.mjs, so this table was the one
  // spot where these figures could drift unnoticed. Same failure the check exists to prevent, one file over.
  const readme = readFileSync(README, 'utf8');
  const rows = [
    ['REVIEW_DEADLINE_MS', 'DEADLINE_MS'],
    ['REVIEW_JOB_BUDGET_MS', 'JOB_BUDGET_MS'],
    ['REVIEW_VERIFY_BUDGET_MS', 'VERIFY_BUDGET_MS'],
  ];
  for (const [envName] of rows) {
    const row = new RegExp(`\\| \`${envName}\` \\| (\\d+) min`).exec(readme);
    assert.ok(row, `the knob table has no row for ${envName}`);
    assert.equal(Number(row[1]), budgetMinutes(envName), `the README says ${envName} is ${row[1]} min`);
  }
  // The verification cap, which the README now states in prose ("up to 20 still-open threads"). A number written
  // in a document is a number that can drift: this is the same check, one sentence over.
  const cap = /judges up to (\d+) still-open threads/.exec(readme);
  assert.ok(cap, 'the README no longer says how many threads a round judges');
  const capInCode = /const MAX_VERIFY_THREADS = (\d+);/.exec(readFileSync(HARNESS, 'utf8'));
  assert.ok(capInCode, 'could not find MAX_VERIFY_THREADS in review.mjs');
  assert.equal(Number(cap[1]), Number(capInCode[1]), `the README says ${cap[1]} threads, the code says ${capInCode[1]}`);

  // And the turn limit, which is written in two places at once: the code's default and the workflow's override.
  const turns = /\| `REVIEW_MAX_TURNS` \| (\d+) in code, (\d+) in the workflow \|/.exec(readme);
  assert.ok(turns, 'the knob table has no REVIEW_MAX_TURNS row');
  const codeDefault = /num\(process\.env\.REVIEW_MAX_TURNS, (\d+)\)/.exec(readFileSync(HARNESS, 'utf8'));
  assert.ok(codeDefault, "could not find REVIEW_MAX_TURNS's default in review.mjs");
  assert.equal(Number(turns[1]), Number(codeDefault[1]), 'the README disagrees with the code about the turn limit');
  const inWorkflow = /REVIEW_MAX_TURNS: '(\d+)'/.exec(readFileSync(WORKFLOW, 'utf8'));
  assert.ok(inWorkflow, 'the workflow no longer sets REVIEW_MAX_TURNS');
  assert.equal(Number(turns[2]), Number(inWorkflow[1]), 'the README disagrees with the workflow about the turn limit');
});

test('a cap claimed in prose is written where the drift check can read it', () => {
  // Fourth version of this check, and the first that is not a list of phrasings. Matching known wordings —
  // "capped at N minutes", then "job cap of N" — meant each new way of writing the same claim was invisible
  // until it drifted: "its 24-minute step timeout" was, and a stale "a 14-minute timeout" had been sitting in
  // review.mjs since the cap was 14. So the claim is what is detected now: a minute figure on a line that also
  // says cap or timeout. Either write it as `the job's N` / `the review step's N`, which the check above
  // verifies, or do not put the number in prose at all.
  const exempt = [
    /^\s*timeout-minutes:/,          // the YAML key IS the source of truth
    /^\s*\|/,                        // the README's knob table, pinned by the test above
    /~\s*\d/,                        // "~1 min of setup" is an estimate of duration, not a claim about a cap
  ];
  const claim = /\b\d+[- ]min(?:ute)?s?\b/i;
  const aboutACap = /\b(cap|capped|timeout)\b/i;
  const canonical = /the (?:job|review step)'s \d+/;

  const offenders = [];
  for (const file of capSources()) {
    const name = file.split('/').slice(-1)[0];
    // This file is exempt from ITS OWN offender scan, and only from that one: its comment necessarily quotes the
    // phrasings it refuses, and a check that cannot describe what it refuses is worse than one with an exemption
    // it names. The canonical-number check above still reads it, so a figure written here in the checked form
    // must still be the real one.
    if (name === 'workflow.test.mjs') continue;
    for (const [i, line] of readFileSync(file, 'utf8').split('\n').entries()) {
      if (exempt.some((re) => re.test(line))) continue;
      if (claim.test(line) && aboutACap.test(line) && !canonical.test(line)) {
        offenders.push(`${name}:${i + 1}: ${line.trim().slice(0, 100)}`);
      }
    }
  }
  assert.deepEqual(offenders, [], `write a cap as \`the job's N\` / \`the review step's N\`, or leave the number out:\n${offenders.join('\n')}`);
});

test('ci.yml cancels superseded runs and does not lend them a token', () => {
  // Two facts a comment in that file now depends on. The `!cancelled()` reasoning on the harness steps says a
  // superseded run should stop — which was not true of a workflow that declared no `concurrency` at all, and the
  // sentence was the only thing claiming it. And the job runs PR-authored code (Gradle, and a suite that shells
  // out), so the checkout must not leave the job token in `.git/config` while it does.
  const ci = readFileSync(CI, 'utf8');
  // Conditioned on the event, not a bare `true`. Cancelling on `push` means a merge commit's build is killed when
  // a second pull request merges behind it, so the commit that is actually on the branch never gets a completed
  // required check — and that run is the one that catches two pull requests that were each green on their own.
  assert.match(ci, /^concurrency:\n(?:\s*#.*\n)*\s+group: .+\n\s+cancel-in-progress: /m, 'ci.yml declares no concurrency, so nothing is ever superseded');
  assert.match(ci, /cancel-in-progress: \$\{\{ github\.event_name == 'pull_request' \}\}/, 'a bare `true` also cancels branch builds after a merge');
  // And a pushed commit needs a group of its OWN. `cancel-in-progress: false` protects a RUNNING build; GitHub
  // still cancels a PENDING one when a newer run is queued into the same group, so three merges in a row left the
  // middle commit without a completed required check — the outcome the setting above is there to prevent.
  assert.match(ci, /group: .*github\.sha/, 'pushed commits share a concurrency group, so a pending build can still be cancelled');
  assert.match(ci, /uses: actions\/checkout@v\d+\n\s+with:\n\s+persist-credentials: false/, 'the checkout leaves the job token in .git/config while PR code runs');
});
