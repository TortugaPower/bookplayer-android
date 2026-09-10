// An end-to-end round: the real GitHub client and the real composition, a stubbed `fetch`, a faked model.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const MAIN = '../review.mjs';

// The module reads PR_NUMBER, COMMIT and RUNNER_TEMP at import time, so the environment is set first and the
// module imported fresh per scenario with a cache-busting query.
async function loadHarness(env, tag) {
  const previous = {};
  for (const [k, v] of Object.entries(env)) {
    previous[k] = process.env[k];
    if (v === undefined) delete process.env[k];
    else process.env[k] = v;
  }
  const mod = await import(`${MAIN}?integration=${tag}`);
  return { mod, restore: () => { for (const [k, v] of Object.entries(previous)) { if (v === undefined) delete process.env[k]; else process.env[k] = v; } } };
}

// A GitHub the harness can talk to: records every write, serves the PR, the diff, the comments and the threads.
function fakeGitHub({ summaryBody = null, threads = [] } = {}) {
  // Big enough that a truncated write is visible: the harness hands the agent a FILE, and nothing else in the
  // suite compares what lands on disk with what GitHub returned.
  const diffBody = `diff --git a/x b/x\n@@ -1 +1 @@\n+x\n${Array.from({ length: 200 }, (_, i) => `+line ${i} of a diff long enough to notice losing`).join('\n')}\n`;
  const calls = { inline: [], issueComments: [], patched: [], replies: [], resolved: [], unresolved: [], graphql: [] };
  const summary = summaryBody === null ? [] : [{ id: 99, user: { login: 'github-actions[bot]' }, body: summaryBody }];
  const fetch = async (url, init = {}) => {
    const u = String(url);
    const method = init.method || 'GET';
    const body = init.body ? JSON.parse(init.body) : null;
    const ok = (json) => ({ ok: true, status: 200, headers: { get: () => null }, json: async () => json, text: async () => (typeof json === 'string' ? json : JSON.stringify(json)) });
    if (u.endsWith('/graphql')) {
      calls.graphql.push(body.query.slice(0, 40));
      if (/resolveReviewThread/.test(body.query) && !/unresolve/.test(body.query)) { calls.resolved.push(body.variables.threadId); return ok({ data: { resolveReviewThread: {} } }); }
      if (/unresolveReviewThread/.test(body.query)) { calls.unresolved.push(body.variables.threadId); return ok({ data: { unresolveReviewThread: {} } }); }
      return ok({ data: { repository: { pullRequest: { reviewThreads: { nodes: threads, pageInfo: { hasNextPage: false, endCursor: null } } } } } });
    }
    if (/\/pulls\/\d+$/.test(u) && (init.headers?.Accept || '').includes('diff')) return ok(diffBody);
    if (/\/pulls\/\d+$/.test(u)) return ok({ title: 'a PR', body: 'a description', user: { login: 'gianni' } });
    if (/\/issues\/\d+\/comments/.test(u) && method === 'GET') return ok(summary);
    if (/\/issues\/\d+\/comments/.test(u) && method === 'POST') { calls.issueComments.push(body.body); return ok({ id: 100 }); }
    if (/\/issues\/comments\/\d+/.test(u) && method === 'PATCH') { calls.patched.push(body.body); return ok({ id: 99 }); }
    if (/\/pulls\/\d+\/comments\/\d+\/replies/.test(u)) { calls.replies.push(body.body); return ok({ id: 101 }); }
    if (/\/pulls\/\d+\/comments/.test(u) && method === 'POST') { calls.inline.push({ path: body.path, line: body.line, body: body.body, commit_id: body.commit_id, side: body.side }); return ok({ id: 102 }); }
    throw new Error(`unstubbed ${method} ${u}`);
  };
  return { calls, fetch, diffBody, summaryOut: () => calls.patched[calls.patched.length - 1] ?? calls.issueComments[calls.issueComments.length - 1] };
}

const agentReturning = (result) => async () => ({ finalText: '```json\n' + JSON.stringify(result) + '\n```', lastAnswer: '', turns: 3, resultSubtype: 'success' });
// The review pass and the verification pass are two calls to the same agent seam, and they want different
// answers: this hands them out in order (the last one repeats, so a round that only reviews still works).
const agentSequence = (...results) => {
  let i = 0;
  return async () => {
    const result = results[Math.min(i++, results.length - 1)];
    return { finalText: '```json\n' + JSON.stringify(result) + '\n```', lastAnswer: '', turns: 3, resultSubtype: 'success' };
  };
};

test('a whole round: findings posted, the record written, an unjudged thread left alone', async () => {
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'integ-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '7', COMMIT: 'abcdef1234567890',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'round1');
  const realFetch = globalThis.fetch;
  try {
    const fresh = { severity: 'error', file: 'app/New.kt', line: 4, comment: 'a new error worth posting' };
    const gone = { severity: 'warn', file: 'app/Old.kt', line: 9, comment: 'a finding this run no longer reports' };
    const goneFp = mod.fingerprint(gone);
    const gh = fakeGitHub({
      threads: [{
        id: 'T-gone', isResolved: false, path: gone.file, line: gone.line, originalLine: gone.line,
        first: { nodes: [{ databaseId: 11, body: `🟡 **WARN** — ${gone.comment} <!-- bp-ai-review-fp:${goneFp} -->`, author: { login: 'github-actions[bot]' } }] },
        comments: { nodes: [] }, last: { nodes: [] },
      }],
    });
    globalThis.fetch = gh.fetch;
    // The verify pass gets no budget here (the agent returns instantly, but VERIFY needs > 60s of job budget,
    // which it has) — so it runs and is asked about T-gone; the fake agent answers for the review only, so the
    // verifier's answer does not parse and the pass degrades. That is the case that used to auto-resolve T-gone.
    await mod.runReview({ agent: agentReturning({ verdict: 'warn', summary: 'one new error', findings: [fresh] }) });

    // The new finding is posted inline, anchored at the head commit.
    assert.deepEqual(gh.calls.inline.map((c) => [c.path, c.line]), [[fresh.file, fresh.line]]);
    // The thread the verification pass owns but could not judge is NOT resolved by silence.
    assert.deepEqual(gh.calls.resolved, []);
    // The summary carries the record, with the posted finding and its thread-less state.
    const summary = gh.summaryOut();
    const state = mod.decodeState(summary);
    assert.ok(state, 'the round must leave a state record');
    assert.equal(state.commit, 'abcdef1234567890');
    assert.equal(state.findings[mod.fingerprint(fresh)].action, 'posted');
    // And the summary says the earlier finding went unjudged rather than pretending it was handled.
    assert.match(summary, /not checked this round/);
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('the record from the last round decides what reopens, with no fingerprint in any body', async () => {
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'integ2-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '8', COMMIT: 'fedcba0987654321',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'round2');
  const realFetch = globalThis.fetch;
  try {
    const back = { severity: 'warn', file: 'app/Back.kt', line: 12, comment: 'a finding that came back' };
    const fp = mod.fingerprint(back);
    // Last round: we closed its thread ourselves. The bodies carry NO fingerprint and NO marker — only the
    // record knows. Before the record, this thread could not be recognised at all.
    const priorSummary = `## ✅ Claude PR Review\n\nprose\n\n<!-- bp-ai-review-summary -->\n${mod.encodeState({
      commit: 'aaaaaaa', findings: { [fp]: { id: 'T-back', file: back.file, line: back.line, severity: 'warn', text: back.comment, action: 'resolved', commit: 'aaaaaaa' } },
    })}`;
    const gh = fakeGitHub({
      summaryBody: priorSummary,
      threads: [{
        id: 'T-back', isResolved: true, path: back.file, line: back.line, originalLine: back.line,
        first: { nodes: [{ databaseId: 21, body: 'the body was edited and says nothing useful', author: { login: 'github-actions[bot]' } }] },
        comments: { nodes: [{ databaseId: 21, body: 'edited', author: { login: 'github-actions[bot]' }, authorAssociation: 'NONE', createdAt: '2026-01-01T00:00:00Z' }] },
        last: { nodes: [{ body: 'edited', author: { login: 'github-actions[bot]' }, createdAt: '2026-01-01T00:00:00Z' }] },
      }],
    });
    globalThis.fetch = gh.fetch;
    await mod.runReview({ agent: agentReturning({ verdict: 'warn', summary: 'it is back', findings: [back] }) });

    // Recognised from the record alone: the thread reopens once, and nothing is posted twice.
    assert.deepEqual(gh.calls.unresolved, ['T-back']);
    assert.deepEqual(gh.calls.inline, []);
    assert.match(gh.calls.replies.join('\n'), /reported again/i);
    // The new record says it is being carried on that thread again.
    const state = mod.decodeState(gh.summaryOut());
    assert.equal(state.findings[fp].id, 'T-back');
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a finding that moved: the verifier calls it a duplicate and the old thread closes after the new comment lands', async () => {
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'integ3-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '9', COMMIT: '1122334455667788',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'round3');
  const realFetch = globalThis.fetch;
  try {
    const text = 'the deadline is read before the message in hand, so a finished run is relabelled';
    const oldF = { severity: 'warn', file: 'app/Moved.kt', line: 5, comment: text };
    const newF = { severity: 'warn', file: 'app/Moved.kt', line: 41, comment: `${text} (still)` };
    const oldFp = mod.fingerprint(oldF);
    const priorSummary = `## 🟡 Claude PR Review\n\nprose\n\n<!-- bp-ai-review-summary -->\n${mod.encodeState({
      commit: 'aaaaaaa',
      findings: { [oldFp]: { id: 'T-moved', file: oldF.file, line: oldF.line, severity: 'warn', text, action: 'posted', commit: 'aaaaaaa' } },
    })}`;
    const thread = {
      id: 'T-moved', isResolved: false, path: oldF.file, line: oldF.line, originalLine: oldF.line,
      first: { nodes: [{ databaseId: 31, body: `🟡 **WARN** — ${text} <!-- bp-ai-review-fp:${oldFp} -->`, author: { login: 'github-actions[bot]' } }] },
      comments: { nodes: [] }, last: { nodes: [] },
    };
    const gh = fakeGitHub({ summaryBody: priorSummary, threads: [thread] });
    globalThis.fetch = gh.fetch;
    // The verifier is shown this push's findings for the file and answers with the line it duplicates.
    await mod.runReview({
      agent: agentSequence(
        { verdict: 'warn', summary: 'it moved', findings: [newF] },
        { threads: [{ id: 1, status: 'duplicate', of: 41, evidence: 'the same leak, now reported at line 41' }] },
      ),
    });

    // The finding is posted where the code is now, and the old thread closes — but only because the new comment
    // landed first: the close is applied after reconcile, never on the strength of an intention.
    assert.deepEqual(gh.calls.inline.map((c) => c.line), [41]);
    assert.deepEqual(gh.calls.resolved, ['T-moved']);
    assert.match(gh.calls.replies.join('\n'), /same issue is reported on this push at line 41/);

    const summary = gh.summaryOut();
    // Reported in the table AND counted once: a row without the flag is counted by the closer and again as
    // "verified closed".
    assert.match(summary, /duplicate of the finding reported at line 41/);
    assert.match(summary, /1 resolved/);
    assert.equal(summary.includes('verified closed'), false);
    // And the record moves with it: the new fingerprint on the thread that now carries the finding, and the
    // close recorded against the old one so a return reopens it rather than reading as a human's decision.
    const state = mod.decodeState(summary);
    assert.equal(state.findings[mod.fingerprint(newF)].action, 'posted');
    assert.equal(state.findings[oldFp].action, 'duplicate');
    assert.equal(state.findings[oldFp].id, 'T-moved');
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a duplicate verdict is refused when its replacement never landed, or names a finding this push lacks', async () => {
  // The gate the resemblance rule had, kept where the decision now lives: a thread may only be closed in favour
  // of a comment that is really there. A post can 422 on a line outside the diff or hit the inline cap, and the
  // model can also name a line this push never reported — `of` is model output, so it is looked up, not trusted.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'dupguard-')));
  const env = {
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '18', COMMIT: 'aced000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  };
  const { mod, restore } = await loadHarness(env, 'dupguard');
  const realFetch = globalThis.fetch;
  try {
    const text = 'the listener is added in onStart and never removed';
    const oldF = { severity: 'warn', file: 'app/Dup.kt', line: 5, comment: text };
    const newF = { severity: 'warn', file: 'app/Dup.kt', line: 41, comment: `${text} (still)` };
    const oldFp = mod.fingerprint(oldF);
    const threadOf = () => ({
      id: 'T-dup', isResolved: false, path: oldF.file, line: oldF.line, originalLine: oldF.line,
      first: { nodes: [{ databaseId: 51, body: `🟡 **WARN** — ${text} <!-- bp-ai-review-fp:${oldFp} -->`, author: { login: 'github-actions[bot]' } }] },
      comments: { nodes: [] }, last: { nodes: [] },
    });

    // (a) the replacement cannot be posted: nothing is closed, and the summary says why.
    const lost = fakeGitHub({ threads: [threadOf()] });
    const inner = lost.fetch;
    globalThis.fetch = async (url, init = {}) => {
      if (/\/pulls\/\d+\/comments$/.test(String(url)) && (init.method || 'GET') === 'POST') throw new Error('422 line not in diff');
      return inner(url, init);
    };
    await mod.runReview({
      agent: agentSequence(
        { verdict: 'warn', summary: 'it moved', findings: [newF] },
        { threads: [{ id: 1, status: 'duplicate', of: 41, evidence: 'same issue at 41' }] },
      ),
    });
    assert.deepEqual(lost.calls.resolved, []);
    assert.match(lost.summaryOut(), /never landed/);

    // (b) the verdict names a line this push does not report: refused, and the thread is reported still open.
    const bogus = fakeGitHub({ threads: [threadOf()] });
    globalThis.fetch = bogus.fetch;
    await mod.runReview({
      agent: agentSequence(
        { verdict: 'warn', summary: 'it moved', findings: [newF] },
        { threads: [{ id: 1, status: 'duplicate', of: 999, evidence: 'same issue somewhere' }] },
      ),
    });
    assert.deepEqual(bogus.calls.resolved, []);
    assert.match(bogus.summaryOut(), /a finding this push does not contain/);

    // (c) the line exists this push, but in ANOTHER FILE: also refused. The prompt only offers same-file
    // findings, so this is the model misreading its own list — and closing a thread in favour of a finding
    // somewhere else entirely is the same class of wrong close the resemblance rule used to make.
    const elsewhere = fakeGitHub({ threads: [threadOf()] });
    globalThis.fetch = elsewhere.fetch;
    await mod.runReview({
      agent: agentSequence(
        { verdict: 'warn', summary: 'two files', findings: [{ severity: 'warn', file: 'app/Other.kt', line: 41, comment: 'a finding in another file at the same line' }] },
        { threads: [{ id: 1, status: 'duplicate', of: 41, evidence: 'line 41 somewhere' }] },
      ),
    });
    assert.deepEqual(elsewhere.calls.resolved, []);
    assert.match(elsewhere.summaryOut(), /a finding this push does not contain/);
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('three rounds in a row: the record the harness wrote is the record it reads', async () => {
  // Every other end-to-end test feeds the harness a prior summary written BY HAND. That pins the shape a test
  // author believes in, not the shape the harness produces: an encode/decode drift, a budget that truncates, a
  // field renamed on one side only, all survive it. Here round N's real output is round N+1's real input, and the
  // threads are the ones round N actually posted.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'chain-')));
  const env = {
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '11', COMMIT: 'c0ffee0000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  };
  const { mod, restore } = await loadHarness(env, 'chain');
  const realFetch = globalThis.fetch;
  try {
    const f = { severity: 'error', file: 'app/Chain.kt', line: 8, comment: 'a finding that lives across three rounds' };
    const fp = mod.fingerprint(f);
    const answer = agentReturning({ verdict: 'fail', summary: 'one error', findings: [f] });

    // ---- Round 1: nothing exists yet.
    const r1 = fakeGitHub();
    globalThis.fetch = r1.fetch;
    await mod.runReview({ agent: answer });
    assert.equal(r1.calls.inline.length, 1, 'round 1 posts the finding');
    const summary1 = r1.summaryOut();
    const state1 = mod.decodeState(summary1);
    assert.equal(state1.findings[fp].action, 'posted');

    // The thread round 1 created, as GitHub would return it next time — including the body it actually wrote.
    const posted = r1.calls.inline[0];
    const thread = (isResolved, extraComments = []) => ({
      id: 'T-chain', isResolved, path: posted.path, line: posted.line, originalLine: posted.line,
      first: { nodes: [{ databaseId: 500, body: posted.body, author: { login: 'github-actions[bot]' } }] },
      comments: { nodes: extraComments }, last: { nodes: extraComments.slice(-1) },
    });

    // ---- Round 2: the same finding, on the summary and thread round 1 left behind.
    const r2 = fakeGitHub({ summaryBody: summary1, threads: [thread(false)] });
    globalThis.fetch = r2.fetch;
    await mod.runReview({ agent: answer });
    assert.deepEqual(r2.calls.inline, [], 'round 2 must not post a second comment for the same finding');
    assert.deepEqual(r2.calls.resolved, []);
    assert.deepEqual(r2.calls.unresolved, []);
    const summary2 = r2.summaryOut();
    const state2 = mod.decodeState(summary2);
    // Recognised, and the record still names the thread that carries it — this is the fact rounds 3+ depend on.
    assert.equal(state2.findings[fp].id, 'T-chain');
    assert.match(summary2, /1 carried over/);

    // ---- Round 3: the finding is gone from the run. It is NOT closed on that silence: the verification pass
    // owns it, and the fake agent's answer does not parse as a verdict list, so the pass degrades and nothing
    // is resolved.
    const r3 = fakeGitHub({ summaryBody: summary2, threads: [thread(false)] });
    globalThis.fetch = r3.fetch;
    await mod.runReview({ agent: agentReturning({ verdict: 'pass', summary: 'nothing new', findings: [] }) });
    assert.deepEqual(r3.calls.resolved, [], 'absence never closes a thread');
    const summary3 = r3.summaryOut();
    assert.match(summary3, /not checked this round/);
    // The record is still there after a round that reported nothing, and it still knows the thread.
    const state3 = mod.decodeState(summary3);
    assert.ok(state3, 'a round with no findings still leaves a record');
    assert.equal(state3.findings[fp]?.id, 'T-chain', 'the open thread survives a round that did not re-report it');

    // ---- Round 4: the finding is back, and a maintainer has EDITED the comment body, so the fingerprint marker
    // the fallback relies on is gone. Only the record — carried through the quiet round 3 — can still say which
    // thread this is. Without the carry-forward the harness posts a second comment for the same finding.
    const edited = {
      id: 'T-chain', isResolved: false, path: posted.path, line: posted.line, originalLine: posted.line,
      first: { nodes: [{ databaseId: 500, body: 'I rewrote this comment while triaging', author: { login: 'github-actions[bot]' } }] },
      comments: { nodes: [] }, last: { nodes: [] },
    };
    const r4 = fakeGitHub({ summaryBody: summary3, threads: [edited] });
    globalThis.fetch = r4.fetch;
    await mod.runReview({ agent: answer });
    assert.deepEqual(r4.calls.inline, [], 'the thread is recognised from the record alone, so nothing is posted twice');
    assert.match(r4.summaryOut(), /1 carried over/);
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('an error thread whose body was edited is not closed by the verifier', async () => {
  // The severity that decides whether `not_applicable` may close a thread has to come from the record, because
  // the body it used to come from is editable. This is the composition half of that: `main` has to hand the
  // verification pass the identity `planRound` computed, and no unit test can see whether it does.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'guard-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '12', COMMIT: 'abc1230000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'guard');
  const realFetch = globalThis.fetch;
  try {
    const err = { severity: 'error', file: 'app/Guard.kt', line: 12, comment: 'the audio session is never deactivated' };
    const fp = mod.fingerprint(err);
    const priorSummary = `## 🔴 Claude PR Review\n\nprose\n\n<!-- bp-ai-review-summary -->\n${mod.encodeState({
      commit: 'aaaaaaa', findings: { [fp]: { id: 'T-err', file: err.file, line: err.line, severity: 'error', text: err.comment, action: 'posted', commit: 'aaaaaaa' } },
    })}`;
    const gh = fakeGitHub({
      summaryBody: priorSummary,
      threads: [{
        id: 'T-err', isResolved: false, path: err.file, line: err.line, originalLine: err.line,
        // Edited: no severity prefix, no fingerprint marker. Only the record knows what this thread is.
        first: { nodes: [{ databaseId: 41, body: 'I trimmed this while triaging', author: { login: 'github-actions[bot]' } }] },
        comments: { nodes: [] }, last: { nodes: [] },
      }],
    });
    globalThis.fetch = gh.fetch;
    await mod.runReview({
      agent: agentSequence(
        { verdict: 'pass', summary: 'nothing new', findings: [] },
        { threads: [{ id: 1, status: 'not_applicable', evidence: 'the premise no longer holds' }] },
      ),
    });

    // The verifier said "no longer applies"; on an `error` that is not enough, and the thread stays open.
    assert.deepEqual(gh.calls.resolved, []);
    const summary = gh.summaryOut();
    assert.match(summary, /an error closes only on a fix/);
    // The verifier was told what the finding IS, not what the edited body says.
    assert.equal(summary.includes('trimmed this while triaging'), false);
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a round that cannot read the threads keeps the record it read', async () => {
  // The summary comment IS where the record lives, and this write replaces that comment. On the one run that
  // already failed — a transient GraphQL error on the thread listing, the failure the retry ladder exists for —
  // the harness was erasing its own memory, so the NEXT round fell back to reading markers out of comment bodies.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'lost-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '13', COMMIT: 'beef000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'lostrecord');
  const realFetch = globalThis.fetch;
  try {
    const f = { severity: 'warn', file: 'app/Keep.kt', line: 3, comment: 'a finding recorded last round' };
    const fp = mod.fingerprint(f);
    const priorSummary = `## 🟡 Claude PR Review\n\nprose\n\n<!-- bp-ai-review-summary -->\n${mod.encodeState({
      commit: 'aaaaaaa', findings: { [fp]: { id: 'T-keep', file: f.file, line: f.line, severity: 'warn', text: f.comment, action: 'posted', commit: 'aaaaaaa' } },
    })}`;
    const gh = fakeGitHub({ summaryBody: priorSummary });
    // The thread listing fails, twice retried, as GitHub does on a bad minute.
    const inner = gh.fetch;
    globalThis.fetch = async (url, init = {}) => {
      const body = init.body ? JSON.parse(init.body) : null;
      if (String(url).endsWith('/graphql') && /reviewThreads/.test(body?.query || '')) {
        return { ok: false, status: 502, headers: { get: () => null }, json: async () => ({ errors: [{ type: 'SERVICE_UNAVAILABLE' }] }), text: async () => 'bad gateway' };
      }
      return inner(url, init);
    };
    await mod.runReview({ agent: agentReturning({ verdict: 'warn', summary: 'one finding', findings: [f] }) });

    const summary = gh.summaryOut();
    assert.match(summary, /Could not read existing review threads/);
    // The record the round READ is written back unchanged: same commit, same entry, same thread id.
    const state = mod.decodeState(summary);
    assert.ok(state, 'the summary must still carry a record');
    assert.equal(state.commit, 'aaaaaaa');
    assert.equal(state.findings[fp].id, 'T-keep');
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a close whose note never posted is still ours two rounds later', async () => {
  // The nastiest shape the record has to survive. Round A resolves a thread (the verification pass judged it
  // fixed) but the REPLY that carries the marker fails — a resolve can succeed while its note does not. Round B
  // reports nothing. Round C sees the finding again. The close was remembered for exactly one round, so by round
  // C nothing knew the harness had closed it, the unmarked resolve read as a maintainer's own decision, and the
  // finding was filed as "dismissed" — invisible, forever, on every later push.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'unmarked-')));
  const env = {
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '14', COMMIT: 'cafe000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  };
  const { mod, restore } = await loadHarness(env, 'unmarked');
  const realFetch = globalThis.fetch;
  try {
    const f = { severity: 'warn', file: 'app/Unmarked.kt', line: 6, comment: 'a finding that gets fixed, then comes back' };
    const fp = mod.fingerprint(f);
    const priorSummary = `## 🟡 Claude PR Review\n\nprose\n\n<!-- bp-ai-review-summary -->\n${mod.encodeState({
      commit: 'aaaaaaa', findings: { [fp]: { id: 'T-un', file: f.file, line: f.line, severity: 'warn', text: f.comment, action: 'posted', commit: 'aaaaaaa' } },
    })}`;
    // The thread as it looks after an unmarked close: resolved, and the only comment on it is the original —
    // no "verified fixed" note, because that reply failed.
    const thread = {
      id: 'T-un', isResolved: true, path: f.file, line: f.line, originalLine: f.line,
      first: { nodes: [{ databaseId: 61, body: `🟡 **WARN** — ${f.comment} <!-- bp-ai-review-fp:${fp} -->`, author: { login: 'github-actions[bot]' } }] },
      comments: { nodes: [] }, last: { nodes: [] },
    };

    // ---- Round A: the verifier says fixed; the resolve lands, the note does not.
    const a = fakeGitHub({ summaryBody: priorSummary, threads: [{ ...thread, isResolved: false }] });
    const innerA = a.fetch;
    globalThis.fetch = async (url, init = {}) => {
      if (/\/replies$/.test(String(url))) throw new Error('502 while posting the note');
      return innerA(url, init);
    };
    await mod.runReview({
      agent: agentSequence(
        { verdict: 'pass', summary: 'nothing new', findings: [] },
        { threads: [{ id: 1, status: 'fixed', evidence: 'the listener is removed in onCleared' }] },
      ),
    });
    assert.deepEqual(a.calls.resolved, ['T-un'], 'round A resolves it');
    const summaryA = a.summaryOut();
    assert.equal(mod.decodeState(summaryA).findings[fp].action, 'resolved');

    // ---- Round B: a quiet round. The close must still be in the record afterwards.
    const b = fakeGitHub({ summaryBody: summaryA, threads: [thread] });
    globalThis.fetch = b.fetch;
    await mod.runReview({ agent: agentReturning({ verdict: 'pass', summary: 'still nothing', findings: [] }) });
    const summaryB = b.summaryOut();
    assert.equal(mod.decodeState(summaryB).findings[fp]?.action, 'resolved', 'the close survives a quiet round');

    // ---- Round C: the finding is back. It reopens on OUR record, with no marker anywhere.
    const c = fakeGitHub({ summaryBody: summaryB, threads: [thread] });
    globalThis.fetch = c.fetch;
    await mod.runReview({ agent: agentReturning({ verdict: 'warn', summary: 'it is back', findings: [f] }) });
    assert.deepEqual(c.calls.unresolved, ['T-un'], 'the thread reopens instead of being read as a human decision');
    assert.deepEqual(c.calls.inline, [], 'and nothing is posted twice');
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

// A degraded answer, a secret in model output, and malformed findings: three things that only main() decides.
const agentDegraded = (result, resultSubtype) => async () => ({ finalText: '```json\n' + JSON.stringify(result) + '\n```', lastAnswer: '', turns: 3, resultSubtype });

test('a deadline answer closes nothing, however complete it looks', async () => {
  // The whole provisional concept rests on one expression in main(): a finished-looking answer that arrived
  // after the clock ran out is LESS complete than what the agent was about to check, so no earlier finding may
  // be closed on its authority. Emptying the subtype half of that expression left the suite green.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'deadline-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '15', COMMIT: 'dead000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'deadline');
  const realFetch = globalThis.fetch;
  try {
    const text = 'the deadline is read before the message in hand, so a finished run is relabelled';
    const oldF = { severity: 'warn', file: 'app/Moved.kt', line: 5, comment: text };
    const newF = { severity: 'warn', file: 'app/Moved.kt', line: 41, comment: `${text} (still)` };
    const oldFp = mod.fingerprint(oldF);
    const gh = fakeGitHub({
      threads: [{
        id: 'T-old', isResolved: false, path: oldF.file, line: oldF.line, originalLine: oldF.line,
        first: { nodes: [{ databaseId: 71, body: `🟡 **WARN** — ${text} <!-- bp-ai-review-fp:${oldFp} -->`, author: { login: 'github-actions[bot]' } }] },
        comments: { nodes: [] }, last: { nodes: [] },
      }],
    });
    globalThis.fetch = gh.fetch;
    // The same round that closes T-old when the answer is whole (see the "finding that moved" test above).
    await mod.runReview({ agent: agentDegraded({ verdict: 'warn', summary: 'it moved', findings: [newF] }, 'error_deadline') });

    assert.deepEqual(gh.calls.resolved, [], 'a provisional round may not close a thread');
    assert.deepEqual(gh.calls.inline.map((c) => c.line), [41], 'but the findings it did produce are still posted');
    assert.match(gh.summaryOut(), /time limit/);
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a secret in model output is redacted in everything the harness posts', async () => {
  // `redact()` runs at the write boundary — the inline body and the summary — because the model quotes the code
  // it reviews, and this repo's own secret shapes are in that code. Both call sites could be removed with the
  // suite green: the unit tests covered the function, nothing covered its use.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'redact-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '16', COMMIT: 'beef000000000002',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'redact');
  const realFetch = globalThis.fetch;
  try {
    const secret = 'ghp_0123456789abcdefghijklmnopqrstuvwx';
    const gh = fakeGitHub();
    globalThis.fetch = gh.fetch;
    await mod.runReview({
      agent: agentReturning({
        verdict: 'warn',
        summary: `The token \`${secret}\` is committed here.`,
        findings: [{ severity: 'warn', file: 'app/Leak.kt', line: 2, comment: `This is a real token: ${secret}` }],
      }),
    });
    const posted = gh.calls.inline.map((c) => c.body).join('\n');
    assert.equal(posted.includes(secret), false, 'the inline comment carried the secret');
    assert.match(posted, /\[redacted\]/);
    const summary = gh.summaryOut();
    assert.equal(summary.includes(secret), false, 'the summary carried the secret');
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a malformed finding is dropped, and two findings on one line become one comment', async () => {
  // Both are main()'s normalisation, and both mutations were silent: a finding with no usable line posted a
  // comment the API rejects, and two findings that share a file/line/severity (one thread can only carry one)
  // lost the second one outright instead of being merged into it.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'norm-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '17', COMMIT: 'beef000000000003',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'normalise');
  const realFetch = globalThis.fetch;
  try {
    const gh = fakeGitHub();
    globalThis.fetch = gh.fetch;
    await mod.runReview({
      agent: agentReturning({
        verdict: 'warn',
        summary: 'a mixed bag',
        findings: [
          { severity: 'warn', file: 'app/Same.kt', line: 9, comment: 'the first thing wrong here' },
          { severity: 'warn', file: 'app/Same.kt', line: 9, comment: 'the second thing wrong here' },
          { severity: 'warn', file: '', line: 3, comment: 'no file at all' },
          { severity: 'warn', file: 'app/Bad.kt', line: 0, comment: 'no usable line' },
          { severity: 'sev', file: 'app/Bad.kt', line: 4, comment: 'not a severity' },
        ],
      }),
    });
    // One comment for the shared line, carrying BOTH texts; nothing for the three malformed ones.
    assert.deepEqual(gh.calls.inline.map((c) => [c.path, c.line]), [['app/Same.kt', 9]]);
    assert.match(gh.calls.inline[0].body, /the first thing wrong here/);
    assert.match(gh.calls.inline[0].body, /the second thing wrong here/);
    assert.equal(gh.summaryOut().includes('no usable line'), false);
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a round that could not READ the record does not overwrite it', async () => {
  // "The read failed" and "there is no record" are different facts. Treating them alike destroyed the record:
  // the round built a fresh one from nothing and PATCHed it over the real one, so one transient 500 cost every
  // close the harness remembered and every thread identity a maintainer's edit had erased from the bodies.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'readfail-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '19', COMMIT: 'f00d000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'readfail');
  const realFetch = globalThis.fetch;
  try {
    const live = { severity: 'warn', file: 'app/Live.kt', line: 4, comment: 'a finding this round reports again' };
    const fp = mod.fingerprint(live);
    const prior = {
      commit: 'aaaaaaa',
      findings: {
        [fp]: { id: 'T-live', file: live.file, line: live.line, severity: 'warn', text: live.comment, action: 'posted', commit: 'aaaaaaa' },
        ffff: { id: 'T-open', file: 'app/Open.kt', line: 9, severity: 'warn', text: 'still open, nobody mentioned it', action: 'open', commit: 'aaaaaaa' },
        eeee: { id: 'T-closed', file: 'app/Closed.kt', line: 2, severity: 'warn', text: 'closed last round', action: 'resolved', commit: 'aaaaaaa', at: '2026-01-01T00:00:00Z' },
      },
    };
    const priorSummary = `## 🟡 Claude PR Review\n\nprose\n\n<!-- bp-ai-review-summary -->\n${mod.encodeState(prior)}`;
    const gh = fakeGitHub({
      summaryBody: priorSummary,
      // The thread is ours and still open, but a maintainer edited the body, so the fingerprint marker is gone:
      // only the record can identify it, which is exactly what this round could not read.
      threads: [{
        id: 'T-live', isResolved: false, path: live.file, line: live.line, originalLine: live.line,
        first: { nodes: [{ databaseId: 81, body: 'edited while triaging', author: { login: 'github-actions[bot]' } }] },
        comments: { nodes: [] }, last: { nodes: [] },
      }],
    });
    const inner = gh.fetch;
    // The FIRST comments read (the record read) 500s through its retry ladder; the one inside upsertSummary works.
    let reads = 0;
    globalThis.fetch = async (url, init = {}) => {
      const isCommentsRead = /\/issues\/\d+\/comments/.test(String(url)) && (init.method || 'GET') === 'GET';
      if (isCommentsRead && reads++ < 3) return { ok: false, status: 500, headers: { get: () => null }, json: async () => ({}), text: async () => 'boom' };
      return inner(url, init);
    };
    await mod.runReview({ agent: agentReturning({ verdict: 'warn', summary: 'still here', findings: [live] }) });

    const after = mod.decodeState(gh.summaryOut());
    assert.ok(after, 'the summary must still carry a record');
    // Everything this round could not learn about survives...
    assert.equal(after.findings.eeee?.action, 'resolved', 'the remembered close was destroyed');
    assert.equal(after.findings.ffff?.id, 'T-open', 'the carried identity was destroyed');
    // ...and a thread id the record knew is not overwritten by the `null` this blind round produced.
    assert.equal(after.findings[fp].id, 'T-live');
    // The merge is UNDER this round, not over it: what this round learned wins, entry by entry, so the record
    // still describes the commit that was reviewed rather than reverting to the older one.
    assert.equal(after.commit, 'f00d000000000001');
    assert.equal(after.findings[fp].commit, 'f00d000000000001');
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('the --setup-failed mode says why in the log, not only on the PR', async () => {
  // The mode exists for the one failure nothing else can report: a step BEFORE the review (the install, the
  // harness's own tests). Its write goes through `appendNoteToSummary`, which swallows a failure on the
  // grounds that "the run log still carries the reason" — and this was the one path where that was false. With
  // GitHub unreachable it printed nothing, wrote nothing, and exited 0.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'setupfail-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '20', COMMIT: 'add0000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'setupfail');
  const realFetch = globalThis.fetch;
  const realWarn = console.warn;
  const warnings = [];
  const argv = process.argv;
  try {
    globalThis.fetch = async () => { throw new Error('getaddrinfo ENOTFOUND api.github.com'); };
    console.warn = (m) => warnings.push(String(m));
    process.argv = [argv[0], argv[1], '--setup-failed', 'npm ci failed on the lockfile'];
    await mod.runReview({ agent: async () => { throw new Error('the agent must never run in this mode'); } });
    assert.match(warnings.join('\n'), /The reviewer did not run: npm ci failed on the lockfile/);
  } finally {
    globalThis.fetch = realFetch;
    console.warn = realWarn;
    process.argv = argv;
    restore();
  }
});

test('an inline comment is anchored to the head commit, on the right-hand side', async () => {
  // Two one-word mutations — `commitId: COMMIT` → the base sha, and `side: 'RIGHT'` → 'LEFT' — make every
  // inline post 422, so every finding silently becomes a summary-only entry and the PR looks reviewed but
  // carries no comments. The fake used to record only the path, line and body, so neither was visible to it.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'anchor-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '21', COMMIT: 'cafebabe00000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'anchor');
  const realFetch = globalThis.fetch;
  try {
    const gh = fakeGitHub();
    globalThis.fetch = gh.fetch;
    await mod.runReview({ agent: agentReturning({ verdict: 'warn', summary: 'one finding', findings: [{ severity: 'warn', file: 'app/A.kt', line: 12, comment: 'a finding to anchor' }] }) });
    assert.equal(gh.calls.inline.length, 1);
    assert.equal(gh.calls.inline[0].commit_id, 'cafebabe00000001');
    assert.equal(gh.calls.inline[0].side, 'RIGHT');
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a secret quoted in a verifier verdict is redacted in the reply it posts', async () => {
  // The verify replies are write boundaries too, and both `redact()` calls in them could be deleted with the
  // suite green: the model's `evidence` is quoted straight into a public comment.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'vredact-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '22', COMMIT: 'dada000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'vredact');
  const realFetch = globalThis.fetch;
  try {
    const secret = 'ghp_0123456789abcdefghijklmnopqrstuvwx';
    const f = { severity: 'warn', file: 'app/V.kt', line: 3, comment: 'a finding from an earlier push' };
    const fp = mod.fingerprint(f);
    const priorSummary = `## 🟡 Claude PR Review\n\nprose\n\n<!-- bp-ai-review-summary -->\n${mod.encodeState({
      commit: 'aaaaaaa', findings: { [fp]: { id: 'T-v', file: f.file, line: f.line, severity: 'warn', text: f.comment, action: 'posted', commit: 'aaaaaaa' } },
    })}`;
    const gh = fakeGitHub({
      summaryBody: priorSummary,
      threads: [{
        id: 'T-v', isResolved: false, path: f.file, line: f.line, originalLine: f.line,
        first: { nodes: [{ databaseId: 91, body: `🟡 **WARN** — ${f.comment} <!-- bp-ai-review-fp:${fp} -->`, author: { login: 'github-actions[bot]' } }] },
        comments: { nodes: [] }, last: { nodes: [] },
      }],
    });
    globalThis.fetch = gh.fetch;
    await mod.runReview({
      agent: agentSequence(
        { verdict: 'pass', summary: 'nothing new', findings: [] },
        { threads: [{ id: 1, status: 'fixed', evidence: `the token ${secret} was moved to SSM` }] },
      ),
    });
    assert.deepEqual(gh.calls.resolved, ['T-v']);
    const replies = gh.calls.replies.join('\n');
    assert.equal(replies.includes(secret), false, 'the verify reply carried the secret');
    assert.match(replies, /\[redacted\]/);
    assert.equal(gh.summaryOut().includes(secret), false, 'the summary table carried the secret');
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a secret with no recognisable shape is still redacted, because the harness knows its own', async () => {
  // Two defences: patterns for known shapes, and exact-match on the values this job was actually given. The
  // second is the one that catches a token whose shape nothing recognises — a rotated format, an app password,
  // a self-hosted URL — and every test until now used a pattern-shaped secret, so deleting it changed nothing.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'valredact-')));
  const opaque = 'quite-ordinary-looking-string-42';
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '23', COMMIT: 'b0b0000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: opaque, RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'valredact');
  const realFetch = globalThis.fetch;
  try {
    const gh = fakeGitHub();
    globalThis.fetch = gh.fetch;
    await mod.runReview({
      agent: agentReturning({
        verdict: 'warn',
        summary: `The key ${opaque} appears in a test fixture.`,
        findings: [{ severity: 'warn', file: 'app/Key.kt', line: 5, comment: `hardcoded: ${opaque}` }],
      }),
    });
    const posted = gh.calls.inline.map((c) => c.body).join('\n');
    assert.equal(posted.includes(opaque), false, 'the inline comment carried the key this job was given');
    assert.match(posted, /\[redacted\]/);
    assert.equal(gh.summaryOut().includes(opaque), false, 'the summary carried it');
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a provisional round never lets the verifier judge, and a stale entry drops out when the read worked', async () => {
  // Two guards that only main() applies, one on each side of the record.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'guards-')));
  const env = {
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '24', COMMIT: 'ba5e000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  };
  const { mod, restore } = await loadHarness(env, 'guards');
  const realFetch = globalThis.fetch;
  try {
    const old = { severity: 'error', file: 'app/Old.kt', line: 7, comment: 'an error from an earlier push' };
    const oldFp = mod.fingerprint(old);
    const thread = {
      id: 'T-old', isResolved: false, path: old.file, line: old.line, originalLine: old.line,
      first: { nodes: [{ databaseId: 61, body: `🔴 **ERROR** — ${old.comment} <!-- bp-ai-review-fp:${oldFp} -->`, author: { login: 'github-actions[bot]' } }] },
      comments: { nodes: [] }, last: { nodes: [] },
    };

    // (a) A provisional answer — the clock ran out — is less complete than what the agent was about to check.
    // The verification pass must not run on it at all: a partial finding list could have the verifier close a
    // thread as fixed, or as a duplicate of a finding that only happens to be in the truncated list.
    const prov = fakeGitHub({ threads: [thread] });
    globalThis.fetch = prov.fetch;
    let verifyCalls = 0;
    await mod.runReview({
      agent: async (prompt) => {
        const isVerify = prompt.includes('Below are findings reported on it by');
        if (isVerify) verifyCalls++;
        return { finalText: '```json\n' + JSON.stringify(isVerify ? { threads: [{ id: 1, status: 'fixed', evidence: 'x' }] } : { verdict: 'pass', summary: 'partial', findings: [] }) + '\n```', lastAnswer: '', turns: 3, resultSubtype: 'error_deadline' };
      },
    });
    assert.equal(verifyCalls, 0, 'the verifier ran on a provisional round');
    assert.deepEqual(prov.calls.resolved, []);
    assert.match(prov.summaryOut(), /not checked this round/);

    // (b) With the record READ successfully, an entry whose thread is gone from the PR drops out. Merging into
    // the old record unconditionally (rather than only when the read failed) would keep it for ever, and the
    // record's cap would eventually spend itself on threads that no longer exist.
    const priorSummary = `## 🟡 Claude PR Review\n\nprose\n\n<!-- bp-ai-review-summary -->\n${mod.encodeState({
      commit: 'aaaaaaa',
      findings: {
        [oldFp]: { id: 'T-old', file: old.file, line: old.line, severity: 'error', text: old.comment, action: 'posted', commit: 'aaaaaaa' },
        deleted: { id: 'T-gone', file: 'app/Deleted.kt', line: 1, severity: 'warn', text: 'its thread was deleted', action: 'open', commit: 'aaaaaaa' },
      },
    })}`;
    const clean = fakeGitHub({ summaryBody: priorSummary, threads: [thread] });
    globalThis.fetch = clean.fetch;
    await mod.runReview({ agent: agentReturning({ verdict: 'fail', summary: 'still here', findings: [old] }) });
    const after = mod.decodeState(clean.summaryOut());
    assert.equal(after.findings[oldFp].id, 'T-old');
    assert.equal(after.findings.deleted, undefined, 'an entry for a thread that no longer exists was kept');
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('the round arms the clocks and the caps it computes', async () => {
  // The budget functions are pure and pinned; the CALL SITES that arm them were not, and each hands back an
  // unbounded clock: GitHub's retry ladders outside every budget the run has, an agent with no turn cap, or a
  // review that outlasts `timeout-minutes` and is cancelled mid-reconcile.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'clocks-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '25', COMMIT: 'c10c000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(), REVIEW_MODEL: 'claude-opus-5-test', REVIEW_MAX_TURNS: '7',
  }, 'clocks');
  // No cache-buster: `review.mjs` imports './github.mjs' by plain specifier, so every cache-busted copy of the
  // harness shares ONE client instance — which is the instance whose clock we are checking.
  const { networkDeadlineForTest } = await import('../github.mjs');
  const realFetch = globalThis.fetch;
  try {
    const gh = fakeGitHub();
    globalThis.fetch = gh.fetch;
    const budgets = [];
    const startedAt = Date.now();
    await mod.runReview({
      agent: async (prompt, budgetMs) => {
        budgets.push(budgetMs);
        return { finalText: '```json\n' + JSON.stringify({ verdict: 'pass', summary: 'fine', findings: [] }) + '\n```', lastAnswer: '', turns: 1, resultSubtype: 'success' };
      },
    });
    // The review pass is given a budget derived from the job's, not the raw deadline: it has to leave the
    // verification slice behind, or the two passes together outlast the job.
    assert.equal(budgets.length, 1);
    assert.ok(budgets[0] <= 12 * 60_000, `review budget was ${budgets[0]}`);
    assert.ok(budgets[0] <= 18 * 60_000 - 5 * 60_000, `review budget did not reserve the verify slice: ${budgets[0]}`);
    // And the GitHub client's own wall clock is armed from the same budget, so a retry ladder cannot run past
    // the end of the job.

    // The resolved model and the turn cap reach the SDK options. Dropping either leaves the SDK to pick its own
    // default while `resolveModel`, `REVIEW_MODEL` and the model-unavailable retry become decoration — and the
    // footer still names the model that did not run.
    const q = mod.agentQuery({ userPrompt: 'p', systemPrompt: 's', abort: new AbortController(), env: { PATH: '/usr/bin' } });
    assert.equal(q.options.model, 'claude-opus-5-test');
    assert.equal(q.options.maxTurns, 7);

    // A SMALL job budget must shrink the review's own: the deadline is a ceiling, not the budget. A call site
    // that hands the agent `DEADLINE_MS` directly passes every assertion above and still lets the review run
    // twice as long as the job it lives in.
    const tight = await loadHarness({
      GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '26', COMMIT: 'c10c000000000002',
      BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
      GITHUB_WORKSPACE: process.cwd(), REVIEW_JOB_BUDGET_MS: String(7 * 60_000),
    }, 'clockstight');
    const tightGh = fakeGitHub();
    globalThis.fetch = tightGh.fetch;
    const tightBudgets = [];
    await tight.mod.runReview({
      agent: async (prompt, budgetMs) => {
        tightBudgets.push(budgetMs);
        return { finalText: '```json\n' + JSON.stringify({ verdict: 'pass', summary: 'fine', findings: [] }) + '\n```', lastAnswer: '', turns: 1, resultSubtype: 'success' };
      },
    });
    tight.restore();
    assert.ok(tightBudgets[0] <= 2 * 60_000, `a 7-minute job gave the review ${Math.round(tightBudgets[0] / 1000)}s`);

    const deadline = networkDeadlineForTest();
    assert.ok(Number.isFinite(deadline), 'the network deadline was never armed');
    assert.ok(deadline >= startedAt && deadline <= startedAt + 19 * 60_000, `deadline ${deadline - startedAt}ms after the start`);
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a thin verification slice means the pass is not started at all', async () => {
  // Under a minute of budget the pass is skipped rather than started. Started anyway, it can still return a
  // partial verdict list through the deadline salvage — and the pass is now the only thing that closes a
  // thread, so a rushed judgement is a close nobody would defend. The summary says the threads went unjudged.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'thin-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '27', COMMIT: 'th1n000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
    // The job budget is what the verify slice is carved out of: 61 seconds leaves the review its 60-second
    // floor and the verification pass almost nothing.
    REVIEW_JOB_BUDGET_MS: String(61_000), REVIEW_VERIFY_BUDGET_MS: String(50_000),
  }, 'thin');
  const realFetch = globalThis.fetch;
  try {
    const f = { severity: 'warn', file: 'app/Thin.kt', line: 3, comment: 'a finding from an earlier push' };
    const fp = mod.fingerprint(f);
    const gh = fakeGitHub({
      threads: [{
        id: 'T-thin', isResolved: false, path: f.file, line: f.line, originalLine: f.line,
        first: { nodes: [{ databaseId: 71, body: `🟡 **WARN** — ${f.comment} <!-- bp-ai-review-fp:${fp} -->`, author: { login: 'github-actions[bot]' } }] },
        comments: { nodes: [] }, last: { nodes: [] },
      }],
    });
    globalThis.fetch = gh.fetch;
    let calls = 0;
    await mod.runReview({
      agent: async () => {
        calls++;
        return { finalText: '```json\n' + JSON.stringify({ verdict: 'pass', summary: 'nothing new', findings: [] }) + '\n```', lastAnswer: '', turns: 1, resultSubtype: 'success' };
      },
    });
    assert.equal(calls, 1, 'the verification pass was started on a slice it cannot finish in');
    assert.deepEqual(gh.calls.resolved, []);
    assert.match(gh.summaryOut(), /not checked this round/);
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('DRY_RUN writes nothing at all, and the diff on disk is the whole diff', async () => {
  // The README tells a maintainer to run the harness locally against a real PR with DRY_RUN=1. If that flag
  // stops being read, the "safe" local run posts comments and resolves threads on a live PR. And the diff the
  // agent reads is a FILE: nothing asserted that what lands on disk is what GitHub returned, so the agent could
  // be reviewing the first kilobyte of the PR with the whole suite green.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'dry-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '28', COMMIT: 'd0d0000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: '1',
    GITHUB_WORKSPACE: process.cwd(),
  }, 'dryrun');
  const realFetch = globalThis.fetch;
  try {
    const gh = fakeGitHub();
    const writes = [];
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method || 'GET';
      const body = init.body ? String(init.body) : '';
      if (method !== 'GET' || /resolveReviewThread|unresolveReviewThread/.test(body)) writes.push(`${method} ${String(url)}`);
      return gh.fetch(url, init);
    };
    let seenDiffPath = '';
    let seenPrompt = '';
    await mod.runReview({
      agent: async (prompt) => {
        seenPrompt = prompt;
        seenDiffPath = (/([^\s`'"]*pr-\d+\.diff)/.exec(prompt) || [])[1] || '';
        return { finalText: '```json\n' + JSON.stringify({ verdict: 'warn', summary: 'dry', findings: [{ severity: 'warn', file: 'x', line: 1, comment: 'c' }] }) + '\n```', lastAnswer: '', turns: 1, resultSubtype: 'success' };
      },
    });
    // The GraphQL read of the threads is a POST, so "no writes" is checked by what it would have MUTATED.
    assert.deepEqual(writes.filter((w) => !/graphql$/.test(w)), [], `a dry run wrote: ${writes.join(', ')}`);
    assert.deepEqual(gh.calls.inline, []);
    assert.deepEqual(gh.calls.issueComments, []);
    assert.deepEqual(gh.calls.patched, []);
    assert.deepEqual(gh.calls.resolved, []);

    // A dry run on a DEADLINE-hit answer names the deadline knob, not the turn limit. The banner block says a
    // wrong knob is worse than no knob, and this call site passed `provisional` without `provisionalCause`, so
    // a local run on a truncated or timed-out answer told the reader to bump REVIEW_MAX_TURNS.
    const logs = [];
    const realLog = console.log;
    console.log = (m) => logs.push(String(m));
    try {
      await mod.runReview({
        agent: async () => ({
          finalText: '```json\n' + JSON.stringify({ verdict: 'warn', summary: 'partial', findings: [] }) + '\n```',
          lastAnswer: '', turns: 1, resultSubtype: 'error_deadline',
        }),
      });
    } finally {
      console.log = realLog;
    }
    const printed = logs.join('\n');
    assert.match(printed, /time limit/);
    assert.equal(printed.includes('turn limit'), false, 'the dry run named the wrong knob');

    // The diff handed to the agent is the whole diff GitHub returned, byte for byte.
    const { readFileSync } = await import('node:fs');
    assert.ok(seenDiffPath, 'the prompt named no diff file');
    // The stub diff is deliberately larger than any plausible truncation: a fixture of a few dozen bytes
    // cannot tell "the whole diff" from "the first kilobyte of it".
    assert.equal(readFileSync(seenDiffPath, 'utf8'), gh.diffBody);
    assert.ok(gh.diffBody.length > 4000, `the fixture diff is only ${gh.diffBody.length} bytes`);
    // And the size the prompt quotes is the size of THAT file, measured by the round rather than assumed: the
    // agent budgets its reads against these numbers, so a stale or invented figure is worse than none.
    assert.match(seenPrompt, new RegExp(`${gh.diffBody.length} bytes`));
    assert.match(seenPrompt, new RegExp(`${gh.diffBody.split('\n').length} lines`));
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a malformed PR number is refused before anything is attempted', async () => {
  // `listIssueComments(NaN)` fails, and the note path swallows that failure — a red check with nothing on the
  // PR, which is the invisible failure the whole degrade design exists to prevent.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'prnum-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: 'not-a-number', COMMIT: 'ba11000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'prnum');
  const realFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => { throw new Error('nothing should be fetched'); };
    await assert.rejects(() => mod.runReview({ agent: async () => { throw new Error('the agent should never run'); } }), /PR_NUMBER must be a positive integer/);
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a finding that lands where another one lives gets its own comment', async () => {
  // The collision, end to end, as it happened on this branch's own PR: a thread already carries an `info` at
  // review.mjs:57, and this push reports a DIFFERENT `info` at review.mjs:57. Sharing a fingerprint, the second
  // was read as a re-report of the first — the thread was reopened, the record was overwritten with the new
  // text, and the verification pass (shown the thread's own body, still describing the FIRST finding) closed it
  // as "verified fixed" on evidence about the other issue. One finding, gone without a trace.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'collide-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '29', COMMIT: 'c011000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'collide');
  const realFetch = globalThis.fetch;
  try {
    const at = (comment) => ({ severity: 'info', file: 'app/Collide.kt', line: 57, comment });
    const first = at('`FALLBACK_MODEL` is a hardcoded id and the only recovery path when the lookup fails');
    const second = at('this constant inlines the literal marker instead of interpolating the one declared above');
    const fp = mod.fingerprint(first);
    assert.equal(mod.fingerprint(second), fp); // same file, line and severity: one fingerprint, two findings
    const gh = fakeGitHub({
      threads: [{
        id: 'T-first', isResolved: false, path: first.file, line: first.line, originalLine: first.line,
        first: { nodes: [{ databaseId: 41, body: `🔵 **INFO** — ${first.comment} <!-- bp-ai-review-fp:${fp} -->`, author: { login: 'github-actions[bot]' } }] },
        comments: { nodes: [] }, last: { nodes: [] },
      }],
    });
    globalThis.fetch = gh.fetch;
    await mod.runReview({
      agent: agentSequence(
        { verdict: 'warn', summary: 'a different finding in the same place', findings: [second] },
        { threads: [{ id: 1, status: 'present', evidence: 'the fallback is still a single hardcoded id' }] },
      ),
    });

    // The new finding gets its OWN comment rather than inheriting the thread...
    assert.deepEqual(gh.calls.inline.map((c) => [c.path, c.line]), [[second.file, second.line]]);
    assert.match(gh.calls.inline[0].body, /inlines the literal marker/);
    // ...the old thread is untouched by the reconcile (not reopened, not closed)...
    assert.deepEqual(gh.calls.resolved, []);
    assert.deepEqual(gh.calls.unresolved, []);
    // ...it went to the verification pass instead, which judged it on its own text and left it open...
    const summary = gh.summaryOut();
    assert.match(summary, /still open/);
    // ...and the record holds BOTH, under different keys, with the old thread's own text intact.
    const state = mod.decodeState(summary);
    const entries = Object.entries(state.findings);
    assert.equal(entries.length, 2, `record held ${entries.length} entries: ${JSON.stringify(entries.map(([k, v]) => [k, v.id, v.text.slice(0, 30)]))}`);
    const carried = state.findings[fp];
    assert.equal(carried.id, 'T-first');
    assert.match(carried.text, /FALLBACK_MODEL/);
    const posted = entries.find(([k]) => k !== fp)[1];
    assert.match(posted.text, /inlines the literal marker/);

    // And the same collision when the thread's body has been EDITED past recognition: the comparison then has
    // only the record's text to go on, so the round must hand the record to the check. Passing null instead
    // makes the two findings merge again, silently.
    const prior = `## 🔵 Claude PR Review\n\nprose\n\n<!-- bp-ai-review-summary -->\n${mod.encodeState({
      commit: 'aaaaaaa',
      findings: { [fp]: { id: 'T-first', file: first.file, line: first.line, severity: 'info', text: first.comment, action: 'posted', commit: 'aaaaaaa' } },
    })}`;
    const edited = fakeGitHub({
      summaryBody: prior,
      threads: [{
        id: 'T-first', isResolved: false, path: first.file, line: first.line, originalLine: first.line,
        first: { nodes: [{ databaseId: 41, body: 'I trimmed this while triaging', author: { login: 'github-actions[bot]' } }] },
        comments: { nodes: [] }, last: { nodes: [] },
      }],
    });
    globalThis.fetch = edited.fetch;
    await mod.runReview({
      agent: agentSequence(
        { verdict: 'warn', summary: 'a different finding in the same place', findings: [second] },
        { threads: [{ id: 1, status: 'present', evidence: 'still a single hardcoded id' }] },
      ),
    });
    assert.deepEqual(edited.calls.inline.map((c) => c.line), [second.line], 'the colliding finding did not get its own comment');
    assert.deepEqual(edited.calls.unresolved, []);
    assert.equal(Object.keys(mod.decodeState(edited.summaryOut()).findings).length, 2);
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('the agent is shown what is open, and naming one keeps the finding on its thread', async () => {
  // The protocol end to end: the review prompt lists the open findings, the agent's answer says `same_as`, and
  // the finding stays on the thread it already has even though its line moved and its wording changed — where
  // before, identity was a hash of file+line+severity and this was two comments plus a duplicate verdict.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'sameas-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '30', COMMIT: '5a3e000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'sameas');
  const realFetch = globalThis.fetch;
  try {
    const old = { severity: 'warn', file: 'app/Same.kt', line: 12, comment: 'the broadcast receiver registered in onStart is never unregistered' };
    const fp = mod.fingerprint(old);
    const gh = fakeGitHub({
      threads: [{
        id: 'T-old', isResolved: false, path: old.file, line: old.line, originalLine: old.line,
        first: { nodes: [{ databaseId: 31, body: `🟡 **WARN** — ${old.comment} <!-- bp-ai-review-fp:${fp} -->`, author: { login: 'github-actions[bot]' } }] },
        comments: { nodes: [] }, last: { nodes: [] },
      }],
    });
    globalThis.fetch = gh.fetch;
    let seen = '';
    const moved = { severity: 'warn', file: 'app/Same.kt', line: 96, comment: 'nothing calls unregisterReceiver on the way out, so the onStart registration leaks', same_as: 1 };
    await mod.runReview({
      agent: async (prompt) => {
        if (!prompt.includes('Below are findings reported on it by')) seen = prompt;
        const isVerify = prompt.includes('Below are findings reported on it by');
        const result = isVerify
          ? { threads: [] }
          : { verdict: 'warn', summary: 'it moved and I said so', findings: [moved] };
        return { finalText: '```json\n' + JSON.stringify(result) + '\n```', lastAnswer: '', turns: 2, resultSubtype: 'success' };
      },
    });

    // The prompt offered the open finding, with an id to name.
    assert.match(seen, /<open_findings>/);
    assert.match(seen, /<finding id="1" file="app\/Same.kt" line="12" severity="warn">/);
    assert.match(seen, /never unregistered/);
    // The claim was honoured: no second comment for a finding that already has a thread...
    assert.deepEqual(gh.calls.inline, []);
    // ...and because the thread does not carry the NEW wording, it is told — a decision may not bury text.
    assert.match(gh.calls.replies.join('\n'), /worded differently/);
    assert.match(gh.calls.replies.join('\n'), /unregisterReceiver on the way out/);
    // The record keeps it under the thread's own fingerprint, so the next round starts from the same identity.
    const state = mod.decodeState(gh.summaryOut());
    assert.equal(state.findings[fp].id, 'T-old');
    assert.match(gh.summaryOut(), /1 carried over/);
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a truncated comment listing is not read as "no record"', async () => {
  // The listing stops early when the run is out of budget or hits the page cap, and a partial list looks exactly
  // like a complete one. Every caller is after ONE comment — this harness's summary, which carries the record —
  // so "not found" means either "there is none yet" or "we did not look at all of them", and those lead
  // opposite ways: the second would build a fresh record over the top of the real one and post a second summary
  // beside it. `truncated` now travels with the list, and the round treats it as a failed read.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'trunc-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '31', COMMIT: '7a1c000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'truncated');
  const realFetch = globalThis.fetch;
  const warnings = [];
  const realWarn = console.warn;
  try {
    const f = { severity: 'warn', file: 'app/T.kt', line: 3, comment: 'a finding recorded last round' };
    const fp = mod.fingerprint(f);
    const prior = mod.encodeState({
      commit: 'aaaaaaa',
      findings: { [fp]: { id: 'T-old', file: f.file, line: f.line, severity: 'warn', text: f.comment, action: 'posted', commit: 'aaaaaaa' } },
    });
    const summary = `## 🟡 Claude PR Review\n\nprose\n\n<!-- bp-ai-review-summary -->\n${prior}`;
    const gh = fakeGitHub({ summaryBody: summary });
    const inner = gh.fetch;
    // A PR with more comments than the harness will page through, and the summary on a page it never reaches:
    // every page comes back full, so the listing stops at the cap.
    globalThis.fetch = async (url, init = {}) => {
      const isCommentsRead = /\/issues\/\d+\/comments/.test(String(url)) && (init.method || 'GET') === 'GET';
      if (isCommentsRead) {
        return {
          ok: true, status: 200, headers: { get: () => null },
          json: async () => Array.from({ length: 100 }, (_, i) => ({ id: i, user: { login: 'gianni' }, body: 'chatter' })),
        };
      }
      return inner(url, init);
    };
    console.warn = (m) => warnings.push(String(m));
    await mod.runReview({ agent: agentReturning({ verdict: 'warn', summary: 'still here', findings: [f] }) });
    console.warn = realWarn;

    // The round says so rather than treating the missing record as "there is none"...
    assert.match(warnings.join('\n'), /truncated before a state record was found/);
    // ...and the summary it writes says it may be duplicating one it could not see.
    assert.match(warnings.join('\n'), /may duplicate an existing summary/);
  } finally {
    console.warn = realWarn;
    globalThis.fetch = realFetch;
    restore();
  }
});

test('a degraded round keeps its record intact, and a control character never reaches the log', async () => {
  // Two things only main() puts together. The degrade path builds a body that CARRIES the record, and
  // `upsertSummary` redacts what it is handed — across the blob, unless it is told not to, which deletes every
  // entry between two dangling halves of a key block. And a finding's `file` is model-authored and reaches the
  // run log, where a newline would put that text at the start of a line, which is where the runner reads
  // `::workflow-command::`.
  const temp = realpathSync(mkdtempSync(join(tmpdir(), 'degrade-')));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: '32', COMMIT: 'de9a000000000001',
    BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k', RUN_URL: '', DRY_RUN: undefined,
    GITHUB_WORKSPACE: process.cwd(),
  }, 'degrade');
  const realFetch = globalThis.fetch;
  try {
    // A record whose entries hold the two dangling halves, as per-field redaction legitimately leaves them.
    const prior = mod.encodeState({
      commit: 'aaaaaaa',
      findings: {
        a: { id: 'T1', file: 'app/A.kt', line: 1, severity: 'warn', text: 'the header -----BEGIN PRIVATE KEY----- appears here', action: 'posted', commit: 'aaaaaaa' },
        b: { id: 'T2', file: 'app/B.kt', line: 2, severity: 'warn', text: 'an ordinary finding in between', action: 'posted', commit: 'aaaaaaa' },
        c: { id: 'T3', file: 'app/C.kt', line: 3, severity: 'warn', text: 'and the footer -----END PRIVATE KEY----- here', action: 'posted', commit: 'aaaaaaa' },
      },
    });
    const gh = fakeGitHub({ summaryBody: `## 🟡 Claude PR Review\n\nprose\n\n<!-- bp-ai-review-summary -->\n${prior}` });
    globalThis.fetch = gh.fetch;
    // A round that produces nothing usable takes the degrade path, which re-appends that record inside the body.
    await mod.runReview({ agent: async () => ({ finalText: 'no json here at all', lastAnswer: '', turns: 1, resultSubtype: 'success' }) });
    const after = mod.decodeState(gh.summaryOut());
    assert.ok(after, 'the degraded round left no record');
    assert.equal(Object.keys(after.findings).length, 3, 'the record lost entries to a redaction that spanned it');
    assert.match(gh.summaryOut(), /did not finish|did not run/);

    // And a finding whose file holds a newline is dropped rather than logged.
    const gh2 = fakeGitHub();
    globalThis.fetch = gh2.fetch;
    const warnings = [];
    const realWarn = console.warn;
    console.warn = (m) => warnings.push(String(m));
    try {
      await mod.runReview({
        agent: agentReturning({
          verdict: 'warn',
          summary: 'one good, one hostile',
          findings: [
            { severity: 'warn', file: 'app/Good.kt', line: 3, comment: 'a real finding' },
            { severity: 'warn', file: 'app/Bad.kt\n::error::spoofed', line: 4, comment: 'a finding with a newline in its path' },
          ],
        }),
      });
    } finally {
      console.warn = realWarn;
    }
    assert.deepEqual(gh2.calls.inline.map((c) => c.path), ['app/Good.kt']);
    assert.match(warnings.join('\n'), /control character/);
    // The spoofed text never appears at the start of any logged line.
    for (const w of warnings) assert.equal(/^::/.test(w), false, `a log line began with a workflow command: ${w}`);
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});
