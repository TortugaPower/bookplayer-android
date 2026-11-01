#!/usr/bin/env node
// Submits a release to Google Play: the phone bundle to `production` and the watch bundle to `wear:production`,
// together in ONE edit, as a 1% staged rollout with each track's release notes, verified before and after
// submitting. Run by .github/workflows/release.yml on a `release-*` branch.
//
//   node scripts/play-release.mjs --check-notes   the notes files only, no network (release.yml runs this first)
//   node scripts/play-release.mjs --dry-run       also signs in and reads both tracks; uploads nothing
//   node scripts/play-release.mjs                 the whole submission
//
// Inputs: PLAY_SERVICE_ACCOUNT_JSON (env); versionName/versionCode from app/ and wear/build.gradle.kts; the prodRelease
// bundles and R8 mappings; notes from distribution/whatsnew/{phone,wear}/whatsnew-en-US (written by the release skill).
//
// Order. An edit that is deleted, or whose commit Play refuses, publishes nothing and burns no version code:
//   1. notes present, not empty, at most 500 characters (Play's limit); bundles and mappings built
//   2. open an edit, read both tracks
//   3. upload both bundles and their mappings; Play must report the expected version codes
//   4. each track = its completed release + the new release at 1% with its notes. A rollout still in progress (or
//      halted, or a draft) is replaced, as Play Console does when you release on top of a staged rollout
//   5. read both tracks back inside the edit; anything different → delete the edit and fail
//   6. validate the edit
//   7. commit, sent for review. With a rejected change on file Play refuses that from the API, so the whole edit is
//      redone and committed NOT sent for review, and the summary says to send it from Play Console. Any other failed
//      commit is final; one without Play's refusal (a 5xx, a dropped connection) may have gone through, so it says so.
//      No commit starts more than 20 minutes in, so the job's time limit can't cut one short
//   8. read both tracks back in a fresh edit (deleted afterwards)
// With Managed publishing off, Google's approval publishes the release at 1%; raising it is a separate job.

import { appendFileSync, existsSync, readFileSync } from 'node:fs';
import { createPrivateKey, sign } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const PACKAGE = 'com.tortugapower.audiobookplayer';
export const USER_FRACTION = 0.01;
export const NOTES_LIMIT = 500;
export const NOTES_LANGUAGE = 'en-US';
// No commit starts later than this after signing in: release.yml gives the submit job 30 minutes, so its time limit
// can only land before a commit, never during one
const COMMIT_BY_MINUTES = 20;

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const HOST = 'https://androidpublisher.googleapis.com';
const API = `${HOST}/androidpublisher/v3/applications/${PACKAGE}`;
const UPLOAD_API = `${HOST}/upload/androidpublisher/v3/applications/${PACKAGE}`;

export const TRACKS = [
  { track: 'production', module: 'app', notesFile: 'distribution/whatsnew/phone/whatsnew-en-US' },
  { track: 'wear:production', module: 'wear', notesFile: 'distribution/whatsnew/wear/whatsnew-en-US' },
];

// MARK: - Inputs

export function gradleVersion(module, root = ROOT) {
  const text = readFileSync(path.join(root, module, 'build.gradle.kts'), 'utf8');
  const code = text.match(/^\s*versionCode\s*=\s*(\d+)/m)?.[1];
  const name = text.match(/^\s*versionName\s*=\s*"([^"]+)"/m)?.[1];
  if (!code || !name) throw new Error(`couldn't read versionCode/versionName from ${module}/build.gradle.kts`);
  return { code, name };
}

export function normalizeNotes(text) {
  return text.replace(/\r\n?/g, '\n').trim();
}

/** The notes text, or an error. Play counts characters, so a 500-character emoji-free note is the limit. */
export function readNotes(file, root = ROOT) {
  const full = path.join(root, file);
  if (!existsSync(full)) return { error: `${file} is missing (the release skill writes it)` };
  const text = normalizeNotes(readFileSync(full, 'utf8'));
  if (!text) return { error: `${file} is empty` };
  const length = [...text].length;
  if (length > NOTES_LIMIT) return { error: `${file} is ${length} characters; Play allows ${NOTES_LIMIT}` };
  return { text };
}

export function buildOutputs(module, root = ROOT) {
  return {
    bundle: path.join(root, module, 'build/outputs/bundle/prodRelease', `${module}-prod-release.aab`),
    mapping: path.join(root, module, 'build/outputs/mapping/prodRelease/mapping.txt'),
  };
}

// MARK: - Track logic

export function newRelease(name, versionCode, notes) {
  return {
    name,
    versionCodes: [String(versionCode)],
    status: 'inProgress',
    userFraction: USER_FRACTION,
    releaseNotes: [{ language: NOTES_LANGUAGE, text: notes }],
  };
}

/**
 * The track's releases once `release` is added: its completed release(s) plus the new one. Anything else (a rollout in
 * progress, a halted one, a draft) is replaced, as Play Console does when you release on top of it, and returned in
 * `replaced` so the summary can say so.
 */
export function planTrack(current, release) {
  const releases = current?.releases || [];
  return {
    releases: [...releases.filter((r) => r.status === 'completed'), release],
    replaced: releases.filter((r) => r.status !== 'completed'),
  };
}

/** What differs between the release Play stored on `track` and `expected`; empty when they match. */
export function differences(track, expected) {
  const label = track?.track || '?';
  const stored = (track?.releases || []).find((r) => (r.versionCodes || []).includes(expected.versionCodes[0]));
  if (!stored) return [`${label}: release ${expected.versionCodes[0]} is missing`];
  const found = [];
  if (stored.status !== expected.status) found.push(`status is ${stored.status}, expected ${expected.status}`);
  if (Number(stored.userFraction) !== expected.userFraction) {
    found.push(`userFraction is ${stored.userFraction}, expected ${expected.userFraction}`);
  }
  if (JSON.stringify(stored.versionCodes) !== JSON.stringify(expected.versionCodes)) {
    found.push(`versionCodes are ${JSON.stringify(stored.versionCodes)}, expected ${JSON.stringify(expected.versionCodes)}`);
  }
  if (stored.name !== expected.name) found.push(`name is ${JSON.stringify(stored.name)}, expected ${JSON.stringify(expected.name)}`);
  const notes = (list) => (list || []).map((n) => `${n.language}: ${normalizeNotes(n.text || '')}`).sort();
  if (JSON.stringify(notes(stored.releaseNotes)) !== JSON.stringify(notes(expected.releaseNotes))) {
    found.push(`release notes differ\n    stored:   ${JSON.stringify(notes(stored.releaseNotes))}`
      + `\n    expected: ${JSON.stringify(notes(expected.releaseNotes))}`);
  }
  return found.map((d) => `${label}: ${d}`);
}

export function describeRelease(r) {
  const fraction = r.userFraction !== undefined ? ` at ${Math.round(r.userFraction * 1000) / 10}%` : '';
  return `${r.name || '(unnamed)'} (${(r.versionCodes || []).join(', ')}) ${r.status}${fraction}`;
}

// MARK: - Play API

export async function accessToken(serviceAccountJson) {
  let account;
  try {
    account = JSON.parse(serviceAccountJson);
  } catch {
    // Not the parser's message: it can quote part of the input, and the log masks only the whole key
    throw new Error('PLAY_SERVICE_ACCOUNT_JSON is not valid JSON');
  }
  const now = Math.floor(Date.now() / 1000);
  const b64url = (b) => Buffer.from(b).toString('base64url');
  const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = b64url(JSON.stringify({
    iss: account.client_email,
    scope: 'https://www.googleapis.com/auth/androidpublisher',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600,
  }));
  const signature = sign('RSA-SHA256', Buffer.from(`${header}.${claims}`), createPrivateKey(account.private_key));
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: `${header}.${claims}.${b64url(signature)}`,
    }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`signing in to Google failed: HTTP ${res.status} ${body.error || ''}`);
  return { token: body.access_token, email: account.client_email };
}

export class Play {
  constructor(token) {
    this.token = token;
  }

  async call(method, url, { json, file } = {}) {
    const headers = { Authorization: `Bearer ${this.token}` };
    let body;
    if (file) {
      headers['Content-Type'] = 'application/octet-stream';
      body = readFileSync(file);
    } else if (json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(json);
    }
    const res = await fetch(url, { method, headers, body });
    const text = await res.text();
    let parsed = {};
    try {
      parsed = text ? JSON.parse(text) : {};
    } catch {
      parsed = {};
    }
    if (!res.ok) {
      const where = url.replace(HOST, '').split('?')[0];
      const error = new Error(`${method} ${where} → HTTP ${res.status}: ${parsed.error?.message || text.slice(0, 300)}`);
      error.status = res.status;
      throw error;
    }
    return parsed;
  }

  async openEdit() {
    return (await this.call('POST', `${API}/edits`, { json: {} })).id;
  }

  deleteEdit(edit) {
    return this.call('DELETE', `${API}/edits/${edit}`);
  }

  getTrack(edit, track) {
    return this.call('GET', `${API}/edits/${edit}/tracks/${encodeURIComponent(track)}`);
  }

  putTrack(edit, track, releases) {
    return this.call('PUT', `${API}/edits/${edit}/tracks/${encodeURIComponent(track)}`, { json: { track, releases } });
  }

  validate(edit) {
    return this.call('POST', `${API}/edits/${edit}:validate`);
  }

  commit(edit, notSentForReview) {
    const query = notSentForReview ? '?changesNotSentForReview=true' : '';
    return this.call('POST', `${API}/edits/${edit}:commit${query}`);
  }

  uploadBundle(edit, file) {
    return this.call('POST', `${UPLOAD_API}/edits/${edit}/bundles?uploadType=media`, { file });
  }

  uploadMapping(edit, versionCode, file) {
    return this.call('POST', `${UPLOAD_API}/edits/${edit}/apks/${versionCode}/deobfuscationFiles/proguard?uploadType=media`, { file });
  }
}

/**
 * Why a commit failed. A 4xx is Play refusing it, so nothing was stored: `review` when it refuses only to send it for
 * review, `refused` otherwise. Anything else (a 5xx, a dropped connection) may have committed anyway: `unknown`.
 */
export function commitFailure(error) {
  if (!(error.status >= 400 && error.status < 500)) return 'unknown';
  return /changesNotSentForReview/.test(error.message) ? 'review' : 'refused';
}

/** Runs `work` in a new edit and deletes the edit afterwards unless `work` committed it. */
export async function inEdit(play, work) {
  const edit = await play.openEdit();
  const state = { committed: false };
  try {
    return await work(edit, state);
  } finally {
    if (!state.committed) {
      // stderr: play-rollout.mjs keeps stdout for its result
      await play.deleteEdit(edit).catch((error) => console.error(`  (couldn't delete edit ${edit}: ${error.message})`));
    }
  }
}

// MARK: - Submission

function log(message) {
  console.log(message);
}

class StepError extends Error {
  constructor(step, message, { commitFailure } = {}) {
    super(`${step}: ${message}`);
    this.commitFailure = commitFailure;
  }
}

/** Steps 2–7 in one edit. Returns what was replaced. */
async function submitInEdit(play, plan, { notSentForReview, commitBy }) {
  return inEdit(play, async (edit, state) => {
    log(`Edit ${edit}${notSentForReview ? ' (committing NOT sent for review)' : ''}`);
    const replaced = [];
    for (const item of plan) {
      const current = await play.getTrack(edit, item.track);
      log(`  ${item.track} now: ${(current.releases || []).map(describeRelease).join('; ') || 'empty'}`);

      const outputs = buildOutputs(item.module);
      const uploaded = await play.uploadBundle(edit, outputs.bundle);
      if (String(uploaded.versionCode) !== item.release.versionCodes[0]) {
        throw new StepError('upload', `${item.track}: Play read version code ${uploaded.versionCode} from the bundle, `
          + `expected ${item.release.versionCodes[0]} from ${item.module}/build.gradle.kts`);
      }
      await play.uploadMapping(edit, uploaded.versionCode, outputs.mapping);
      log(`  ${item.track}: uploaded bundle ${uploaded.versionCode} and its mapping`);

      const next = planTrack(current, item.release);
      await play.putTrack(edit, item.track, next.releases);
      replaced.push(...next.replaced.map((r) => `${item.track}: ${describeRelease(r)}`));
    }

    const mismatches = [];
    for (const item of plan) mismatches.push(...differences(await play.getTrack(edit, item.track), item.release));
    if (mismatches.length) throw new StepError('read-back before commit', `\n  ${mismatches.join('\n  ')}`);
    log('  read back inside the edit: both tracks match');

    try {
      await play.validate(edit);
    } catch (error) {
      throw new StepError('validate', error.message);
    }
    log('  edit validates');

    if (Date.now() > commitBy) {
      throw new StepError('commit', `not started: over ${COMMIT_BY_MINUTES} minutes in, too close to the job's time limit`);
    }
    try {
      await play.commit(edit, notSentForReview);
    } catch (error) {
      const failure = commitFailure(error);
      const message = failure === 'unknown'
        ? `${error.message}\n  Play didn't refuse it, so it may have gone through: check both tracks in Play Console before `
          + 'running this again (a re-run after a commit that went through fails with "version code has already been used")'
        : error.message;
      throw new StepError('commit', message, { commitFailure: failure });
    }
    state.committed = true;
    log(`  committed${notSentForReview ? ' (NOT sent for review)' : ', sent for review'}`);
    return replaced;
  });
}

async function verifyAfterCommit(play, plan) {
  return inEdit(play, async (edit) => {
    const mismatches = [];
    for (const item of plan) mismatches.push(...differences(await play.getTrack(edit, item.track), item.release));
    return mismatches;
  });
}

function summary(lines) {
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${lines.join('\n')}\n`);
}

export async function main(argv = process.argv.slice(2)) {
  const mode = { '--check-notes': 'notes', '--dry-run': 'dry-run', undefined: 'submit' }[argv[0]];
  if (!mode) throw new Error('usage: play-release.mjs [--check-notes | --dry-run]');

  // 1. Inputs: nothing reaches Play unless all of them are right
  const problems = [];
  const plan = TRACKS.map((item) => {
    const version = gradleVersion(item.module);
    const notes = readNotes(item.notesFile);
    if (notes.error) problems.push(notes.error);
    return { ...item, version, release: newRelease(version.name, version.code, notes.text || '') };
  });
  if (new Set(plan.map((p) => p.version.name)).size !== 1) {
    problems.push(`versionName differs: ${plan.map((p) => `${p.module} ${p.version.name}`).join(', ')}`);
  }
  if (mode === 'submit') {
    for (const item of plan) {
      for (const file of Object.values(buildOutputs(item.module))) {
        if (!existsSync(file)) problems.push(`${path.relative(ROOT, file)} is missing (build the prodRelease bundle first)`);
      }
    }
  }
  if (problems.length) throw new Error(`can't submit:\n  ${problems.join('\n  ')}`);
  for (const item of plan) log(`${item.track}: ${item.release.name} (${item.version.code}), notes ${[...item.release.releaseNotes[0].text].length} characters`);
  if (mode === 'notes') return;

  if (!process.env.PLAY_SERVICE_ACCOUNT_JSON) throw new Error('PLAY_SERVICE_ACCOUNT_JSON is not set');
  const { token, email } = await accessToken(process.env.PLAY_SERVICE_ACCOUNT_JSON);
  const play = new Play(token);
  log(`Signed in as ${email}`);

  if (mode === 'dry-run') {
    await inEdit(play, async (edit) => {
      for (const item of plan) {
        const next = planTrack(await play.getTrack(edit, item.track), item.release);
        log(`  ${item.track}: would become ${next.releases.map(describeRelease).join('; ')}`
          + `${next.replaced.length ? ` (replacing ${next.replaced.map(describeRelease).join('; ')})` : ''}`);
      }
    });
    log('Dry run: nothing uploaded or submitted');
    return;
  }

  // 2–7. Sent for review first; Play refuses that while a rejected change is on file, so then the whole edit is redone
  // and committed without sending. Only that refusal is retried: it stored nothing
  let replaced;
  let sentForReview = true;
  const commitBy = Date.now() + COMMIT_BY_MINUTES * 60 * 1000;
  try {
    replaced = await submitInEdit(play, plan, { notSentForReview: false, commitBy });
  } catch (error) {
    if (error.commitFailure !== 'review') throw error;
    log(`Play won't send this for review (${error.message}); redoing the edit to commit it without sending`);
    try {
      replaced = await submitInEdit(play, plan, { notSentForReview: true, commitBy });
      sentForReview = false;
    } catch (second) {
      throw new Error(`sent for review, refused: ${error.message}\nnot sent for review: ${second.message}`
        + `${second.commitFailure === 'unknown' ? '' : '\nNothing was submitted.'}`);
    }
  }

  // 8. What Play stored. It's submitted by now, so a failed read-back still gets the summary below
  let mismatches;
  try {
    mismatches = await verifyAfterCommit(play, plan);
  } catch (error) {
    mismatches = [`couldn't read the tracks back: ${error.message}`];
  }
  const lines = [`## Google Play: ${plan[0].release.name} submitted at ${USER_FRACTION * 100}%`, ''];
  lines.push(sentForReview
    ? 'Sent for review. With Managed publishing off, Google\'s approval publishes it to 1% of users.'
    : '**NOT sent for review**: Play has a rejected change on file. Send it for review from Play Console → Publishing overview.');
  lines.push('');
  for (const item of plan) {
    lines.push(`### ${item.track}: ${item.release.name} (${item.version.code})`, '', '```', item.release.releaseNotes[0].text, '```', '');
  }
  if (replaced.length) lines.push(`Replaced: ${replaced.join('; ')}`, '');
  if (mismatches.length) {
    lines.push('**Submitted, but the read-back after committing failed:**', ...mismatches.map((m) => `- ${m}`));
  }
  summary(lines);
  log(lines.join('\n'));
  // A green run would otherwise hide it: a release not sent for review sits there until someone sends it
  if (!sentForReview) log('::warning::Committed but NOT sent for review: send it from Play Console → Publishing overview');
  if (mismatches.length) {
    throw Object.assign(new Error(`submitted, but the read-back after committing failed:\n  ${mismatches.join('\n  ')}`),
      { summarized: true });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(`\n${error.message}`);
    // The run page shows the summary, not the log: a commit that may have gone through must be seen there
    if (!error.summarized) summary(['## Google Play: failed', '', '```', error.message, '```']);
    process.exit(1);
  });
}
