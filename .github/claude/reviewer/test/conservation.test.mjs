// The law: a finding this harness has reported never leaves the pull request silently.
//
// Every foundation failure on this branch has been one shape — the harness acted on an inference, and a wrong
// inference lost a finding without saying so. A bash emulator inferred argv; marker archaeology inferred the
// harness's own history; a similarity score inferred "these two texts are the same finding"; a fingerprint
// inferred identity from a location. Each was fixed by obtaining the fact or refusing to act without it, and
// each was found by someone looking from outside — never by the local loop, because a mutation sweep pins the
// behaviour a design has, and says nothing about whether the design is right.
//
// So this file does not test a mechanism. It states the property all of them exist to serve, and fuzzes rounds
// against it: findings appear, drift to new lines, get reworded, collide on a line another finding already
// occupies; maintainers edit comment bodies and resolve threads; posts, resolves and the record read fail. The
// verifier is scripted to answer `present` for everything — nothing is ever fixed — so NOTHING may be closed,
// and after every round each finding ever reported must still be findable on the PR: carried by exactly one
// open thread, or named in the summary as unpostable or unjudged.
//
// Each finding carries an oracle token (`[F7]`) that survives rewording, so the check is exact string
// containment rather than a judgement of its own. It would have caught all three of today's failures.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const MAIN = '../review.mjs';

// Deterministic RNG: a failing scenario has to be reproducible from its seed alone.
function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 0x100000000;
  };
}

// A GitHub whose state evolves as the harness acts on it: posting opens a thread, replying appends to one,
// resolving flips it. Every earlier test in this suite serves a fixed snapshot, which cannot express "what the
// harness did last round is what it sees this round" — the axis all four failures lived on.
function worldGitHub() {
  let nextComment = 1000;
  let nextThread = 1;
  const state = { threads: [], summary: null, failPost: false, failResolve: false, failRecordRead: false };
  const calls = { posted: 0, resolved: 0, unresolved: 0, replies: 0 };

  const threadNodes = () =>
    state.threads.map((t) => ({
      id: t.id,
      isResolved: t.isResolved,
      path: t.path,
      line: t.outdated ? null : t.line,
      originalLine: t.line,
      first: { nodes: [{ databaseId: t.comments[0].databaseId, body: t.comments[0].body, author: { login: t.comments[0].author } }] },
      comments: { nodes: t.comments.slice(-30).map((c) => ({ databaseId: c.databaseId, body: c.body, author: { login: c.author }, authorAssociation: c.association, createdAt: c.createdAt })) },
      last: { nodes: t.comments.slice(-1).map((c) => ({ body: c.body, author: { login: c.author }, createdAt: c.createdAt })) },
    }));

  const fetch = async (url, init = {}) => {
    const u = String(url);
    const method = init.method || 'GET';
    const body = init.body ? JSON.parse(init.body) : null;
    const ok = (json) => ({ ok: true, status: 200, headers: { get: () => null }, json: async () => json, text: async () => (typeof json === 'string' ? json : JSON.stringify(json)) });
    const fail = (status) => ({ ok: false, status, headers: { get: () => null }, json: async () => ({}), text: async () => 'injected failure' });

    if (u.endsWith('/graphql')) {
      if (/resolveReviewThread/.test(body.query) && !/unresolve/.test(body.query)) {
        if (state.failResolve) return fail(403);
        const t = state.threads.find((x) => x.id === body.variables.threadId);
        if (t) t.isResolved = true;
        calls.resolved++;
        return ok({ data: { resolveReviewThread: {} } });
      }
      if (/unresolveReviewThread/.test(body.query)) {
        if (state.failResolve) return fail(403);
        const t = state.threads.find((x) => x.id === body.variables.threadId);
        if (t) t.isResolved = false;
        calls.unresolved++;
        return ok({ data: { unresolveReviewThread: {} } });
      }
      return ok({ data: { repository: { pullRequest: { reviewThreads: { nodes: threadNodes(), pageInfo: { hasNextPage: false, endCursor: null } } } } } });
    }
    if (/\/pulls\/\d+$/.test(u) && (init.headers?.Accept || '').includes('diff')) return ok('diff --git a/x b/x\n@@ -1 +1 @@\n+x\n');
    if (/\/pulls\/\d+$/.test(u)) return ok({ title: 'a PR', body: 'a description', user: { login: 'author' } });
    if (/\/issues\/\d+\/comments/.test(u) && method === 'GET') {
      if (state.failRecordRead) return fail(500);
      return ok(state.summary ? [{ id: 99, user: { login: 'github-actions[bot]' }, body: state.summary }] : []);
    }
    if (/\/issues\/\d+\/comments/.test(u) && method === 'POST') {
      state.summary = body.body;
      return ok({ id: 99 });
    }
    if (/\/issues\/comments\/\d+/.test(u) && method === 'PATCH') {
      state.summary = body.body;
      return ok({ id: 99 });
    }
    if (/\/pulls\/\d+\/comments\/\d+\/replies/.test(u)) {
      const id = Number(/comments\/(\d+)\/replies/.exec(u)[1]);
      const t = state.threads.find((x) => x.comments[0].databaseId === id);
      if (t) t.comments.push({ databaseId: nextComment++, body: body.body, author: 'github-actions[bot]', association: 'NONE', createdAt: new Date().toISOString() });
      calls.replies++;
      return ok({ id: nextComment });
    }
    if (/\/pulls\/\d+\/comments/.test(u) && method === 'POST') {
      if (state.failPost) return fail(422);
      state.threads.push({
        id: `T${nextThread++}`,
        path: body.path,
        line: body.line,
        isResolved: false,
        outdated: false,
        comments: [{ databaseId: nextComment++, body: body.body, author: 'github-actions[bot]', association: 'NONE', createdAt: new Date().toISOString() }],
      });
      calls.posted++;
      return ok({ id: nextComment });
    }
    throw new Error(`unstubbed ${method} ${u}`);
  };
  return { state, calls, fetch };
}

// The scripted model. The review pass reports the findings the scenario asks for; the verification pass answers
// `present` for every id it is given — nothing is ever fixed, so nothing may ever be closed.
const scriptedAgent = (findings) => async (prompt) => {
  const isVerify = prompt.includes('Below are findings reported on it by');
  const result = isVerify
    ? { threads: [...prompt.matchAll(/<finding id="(\d+)"/g)].map((m) => ({ id: Number(m[1]), status: 'present', evidence: 'the code still does this' })) }
    : { verdict: findings.length ? 'warn' : 'pass', summary: 'a round', findings };
  return { finalText: '```json\n' + JSON.stringify(result) + '\n```', lastAnswer: '', turns: 2, resultSubtype: 'success' };
};

async function loadHarness(env, tag) {
  const previous = {};
  for (const [k, v] of Object.entries(env)) {
    previous[k] = process.env[k];
    if (v === undefined) delete process.env[k];
    else process.env[k] = v;
  }
  const mod = await import(`${MAIN}?conservation=${tag}`);
  return { mod, restore: () => { for (const [k, v] of Object.entries(previous)) { if (v === undefined) delete process.env[k]; else process.env[k] = v; } } };
}

// One scenario: a world of findings, and a sequence of rounds that mutate it the way real pushes do.
async function runScenario(seed) {
  const rand = rng(seed);
  const pick = (arr) => arr[Math.floor(rand() * arr.length)];
  const temp = realpathSync(mkdtempSync(join(tmpdir(), `law-${seed}-`)));
  const { mod, restore } = await loadHarness({
    GITHUB_REPOSITORY: 'TortugaPower/repo', GITHUB_TOKEN: 'tok', PR_NUMBER: String(100 + (seed % 800)),
    COMMIT: `c0${seed}`.padEnd(16, '0'), BASE_REF: 'develop', RUNNER_TEMP: temp, ANTHROPIC_API_KEY: 'k',
    RUN_URL: '', DRY_RUN: undefined, GITHUB_WORKSPACE: process.cwd(),
  }, `s${seed}`);
  const gh = worldGitHub();
  const realFetch = globalThis.fetch;
  globalThis.fetch = gh.fetch;

  const FILES = ['app/A.kt', 'app/B.kt'];
  const SEVERITIES = ['error', 'warn', 'info'];
  // The world's findings. `token` is the oracle's handle on each one and survives every rewording.
  let nextToken = 1;
  const world = [];
  const newFinding = (over = {}) => {
    const token = `[F${nextToken++}]`;
    const f = { token, file: pick(FILES), line: 1 + Math.floor(rand() * 60), severity: pick(SEVERITIES), words: `the ${token} problem is that this call is never released on the lifecycle it belongs to`, reported: false, ...over };
    world.push(f);
    return f;
  };
  for (let i = 0; i < 3; i++) newFinding();

  const asFinding = (f) => ({ severity: f.severity, file: f.file, line: f.line, comment: f.words });
  const problems = [];

  for (let round = 1; round <= 6; round++) {
    // Mutations a real push makes.
    if (rand() < 0.4) { const f = pick(world); f.line = 1 + Math.floor(rand() * 60); }            // the line drifts
    if (rand() < 0.3) { const f = pick(world); f.words = `${f.words} (still true at push ${round})`; } // reworded
    if (rand() < 0.35) {                                                                            // a NEW finding where one already lives
      const host = pick(world.filter((f) => f.reported)) || pick(world);
      newFinding({ file: host.file, line: host.line, severity: host.severity, words: `the [F${nextToken}] problem is a different one entirely: this receiver is registered twice` });
    }
    if (rand() < 0.25) newFinding();
    // A maintainer edits one of our comment bodies past recognition.
    if (rand() < 0.25 && gh.state.threads.length) {
      const t = pick(gh.state.threads);
      t.comments[0].body = 'I rewrote this while triaging';
    }
    // A maintainer resolves one of our threads themselves.
    if (rand() < 0.2 && gh.state.threads.length) {
      const t = pick(gh.state.threads.filter((x) => !x.isResolved) || []);
      if (t) { t.isResolved = true; t.comments.push({ databaseId: 9000 + round, body: 'handled, thanks', author: 'gianni', association: 'OWNER', createdAt: new Date().toISOString() }); }
    }
    // GitHub outdates a thread whose anchor no longer maps.
    if (rand() < 0.2 && gh.state.threads.length) pick(gh.state.threads).outdated = true;
    // Injected failures, one round at a time.
    gh.state.failPost = rand() < 0.15;
    gh.state.failResolve = rand() < 0.15;
    gh.state.failRecordRead = rand() < 0.15;

    // What the model reports this round: a random subset, so "not re-reported" happens constantly.
    const reporting = world.filter(() => rand() < 0.7);
    for (const f of reporting) f.reported = true;
    await mod.runReview({ agent: scriptedAgent(reporting.map(asFinding)) });

    // THE LAW, in two halves.
    //
    // First: every finding the harness is STILL being told about, or that it has a comment for, must be
    // accounted for — carried by exactly one open thread, identified by the record as living on an open thread
    // (which is what happens when a maintainer wipes our comment body), named in the summary, or closed by a
    // human. Never simply absent. A finding the model has stopped reporting and that never got a comment is
    // outside this: the harness has no evidence it is still true and nothing to carry it on.
    //
    // Second: in the round where a finding could NOT be posted, that round's summary has to name it. That is
    // the harness's actual obligation to a finding it could not put inline, and the only thing that keeps the
    // first half honest about the case above.
    const summary = gh.state.summary || '';
    const record = mod.decodeState(summary);
    const recordCarries = (token) =>
      Object.values(record?.findings || {}).some(
        (r) => String(r?.text || '').includes(token) && gh.state.threads.some((t) => t.id === r.id && !t.isResolved),
      );
    for (const f of world) {
      const anyThread = gh.state.threads.some((t) => t.comments.some((c) => c.body.includes(f.token)));
      const reportedNow = reporting.includes(f);
      if (!reportedNow && !anyThread) continue;
      const open = gh.state.threads.filter((t) => !t.isResolved && t.comments.some((c) => c.body.includes(f.token)));
      const closedByHuman = gh.state.threads.some(
        (t) => t.isResolved && t.comments.some((c) => c.body.includes(f.token)) && t.comments.some((c) => c.author !== 'github-actions[bot]'),
      );
      if (open.length === 1 || closedByHuman || summary.includes(f.token) || recordCarries(f.token)) continue;
      const mine = gh.state.threads.filter((t) => t.comments.some((c) => c.body.includes(f.token)));
      problems.push(
        `seed ${seed} round ${round}: ${f.token} (${f.severity} ${f.file}:${f.line}, reported this round: ${reportedNow}) ` +
          `is accounted for nowhere — ${open.length} open thread(s) carry it, ${mine.length - open.length} resolved, ` +
          `in summary: ${summary.includes(f.token)}, in record on an open thread: ${recordCarries(f.token)}`,
      );
    }
    // The second half: a finding reported this round that ended up on no thread must be named in the summary.
    for (const f of reporting) {
      const onAThread = gh.state.threads.some((t) => t.comments.some((c) => c.body.includes(f.token)));
      if (onAThread || summary.includes(f.token) || recordCarries(f.token)) continue;
      problems.push(`seed ${seed} round ${round}: ${f.token} was reported and could not be posted, and the summary does not mention it`);
    }
    // And with the verifier saying "present" about everything, the harness may not close a thread on its own.
    // (A resolve the scenario's maintainer made is not the harness's.)
    const ourCloses = gh.state.threads.filter((t) => t.isResolved && t.comments.every((c) => c.author === 'github-actions[bot]'));
    for (const t of ourCloses) {
      problems.push(`seed ${seed} round ${round}: thread ${t.id} was closed by the harness though the verifier said every finding is still present`);
    }
  }

  globalThis.fetch = realFetch;
  restore();
  return problems;
}

test('no reported finding ever leaves the pull request silently', async () => {
  const found = [];
  for (const seed of [1, 7, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987]) {
    found.push(...(await runScenario(seed)));
  }
  assert.deepEqual(found, [], `the conservation law failed:\n${found.slice(0, 12).join('\n')}`);
});
