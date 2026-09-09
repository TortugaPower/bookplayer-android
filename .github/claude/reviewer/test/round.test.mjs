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
    assert.equal(state.findings[oldFp], undefined);
  } finally {
    globalThis.fetch = realFetch;
    restore();
  }
});
