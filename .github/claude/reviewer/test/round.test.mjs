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
    if (/\/pulls\/\d+$/.test(u) && (init.headers?.Accept || '').includes('diff')) return ok('diff --git a/x b/x\n@@ -1 +1 @@\n+x\n');
    if (/\/pulls\/\d+$/.test(u)) return ok({ title: 'a PR', body: 'a description', user: { login: 'gianni' } });
    if (/\/issues\/\d+\/comments/.test(u) && method === 'GET') return ok(summary);
    if (/\/issues\/\d+\/comments/.test(u) && method === 'POST') { calls.issueComments.push(body.body); return ok({ id: 100 }); }
    if (/\/issues\/comments\/\d+/.test(u) && method === 'PATCH') { calls.patched.push(body.body); return ok({ id: 99 }); }
    if (/\/pulls\/\d+\/comments\/\d+\/replies/.test(u)) { calls.replies.push(body.body); return ok({ id: 101 }); }
    if (/\/pulls\/\d+\/comments/.test(u) && method === 'POST') { calls.inline.push({ path: body.path, line: body.line, body: body.body }); return ok({ id: 102 }); }
    throw new Error(`unstubbed ${method} ${u}`);
  };
  return { calls, fetch, summaryOut: () => calls.patched[calls.patched.length - 1] ?? calls.issueComments[calls.issueComments.length - 1] };
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

test('a finding that moved: the old thread closes, the new one posts, the footer counts it once', async () => {
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
    const gh = fakeGitHub({
      summaryBody: priorSummary,
      threads: [{
        id: 'T-moved', isResolved: false, path: oldF.file, line: oldF.line, originalLine: oldF.line,
        first: { nodes: [{ databaseId: 31, body: `🟡 **WARN** — ${text} <!-- bp-ai-review-fp:${oldFp} -->`, author: { login: 'github-actions[bot]' } }] },
        comments: { nodes: [] }, last: { nodes: [] },
      }],
    });
    globalThis.fetch = gh.fetch;
    await mod.runReview({ agent: agentReturning({ verdict: 'warn', summary: 'it moved', findings: [newF] }) });

    // The finding is posted where the code is now, and the stale anchor is closed as superseded.
    assert.deepEqual(gh.calls.inline.map((c) => c.line), [41]);
    assert.deepEqual(gh.calls.resolved, ['T-moved']);
    assert.match(gh.calls.replies.join('\n'), /different line/);

    const summary = gh.summaryOut();
    // The close is reported in the table AND counted once: a row without the flag is counted by the stale loop
    // and again as "verified closed".
    assert.match(summary, /reported again at a new line/);
    assert.match(summary, /1 resolved/);
    assert.equal(summary.includes('verified closed'), false);
    // And the record moves with it: the new fingerprint on the thread that now carries the finding.
    const state = mod.decodeState(summary);
    assert.equal(state.findings[mod.fingerprint(newF)].action, 'posted');
    // The CLOSE is recorded too. This assertion used to demand the opposite, which is how the record came to
    // carry no close at all: a closed thread's finding is absent from this round's findings, so it reached the
    // record through no other path, `harnessClosedByRecord` always returned null, and the marker archaeology the
    // record replaced was still what ran in production.
    assert.equal(state.findings[oldFp].action, 'superseded');
    assert.equal(state.findings[oldFp].id, 'T-moved');
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
