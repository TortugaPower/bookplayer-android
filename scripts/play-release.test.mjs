// Unit tests for scripts/play-release.mjs (no network). Run with: node --test scripts/play-release.test.mjs
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  NOTES_LIMIT, USER_FRACTION, commitFailure, differences, gradleVersion, newRelease, normalizeNotes, planTrack, readNotes,
} from './play-release.mjs';

function notesDir(files) {
  const root = mkdtempSync(path.join(tmpdir(), 'play-release-'));
  for (const [file, text] of Object.entries(files)) {
    mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    writeFileSync(path.join(root, file), text);
  }
  return root;
}

test('notes: line endings and surrounding whitespace are normalized', () => {
  assert.equal(normalizeNotes('\nBugfixes\r\n\r\n– One\r\n'), 'Bugfixes\n\n– One');
});

test('notes: missing, empty, over the limit, and exactly at the limit', () => {
  const root = notesDir({
    'empty.txt': ' \n',
    'long.txt': 'x'.repeat(NOTES_LIMIT + 1),
    'limit.txt': 'x'.repeat(NOTES_LIMIT),
  });
  assert.match(readNotes('missing.txt', root).error, /missing/);
  assert.match(readNotes('empty.txt', root).error, /empty/);
  assert.match(readNotes('long.txt', root).error, /501 characters; Play allows 500/);
  assert.equal(readNotes('limit.txt', root).text.length, NOTES_LIMIT);
});

test('notes: the limit counts characters, not bytes', () => {
  const root = notesDir({ 'dash.txt': '–'.repeat(NOTES_LIMIT) });
  assert.equal(readNotes('dash.txt', root).error, undefined);
});

test('version: read from the real build files', () => {
  const app = gradleVersion('app');
  const wear = gradleVersion('wear');
  assert.match(app.code, /^\d+$/);
  assert.match(wear.code, /^100\d{3}$/);
  assert.equal(app.name, wear.name);
});

test('new release: 1% staged rollout with en-US notes', () => {
  assert.deepEqual(newRelease('1.4.0', 26, 'Notes'), {
    name: '1.4.0',
    versionCodes: ['26'],
    status: 'inProgress',
    userFraction: USER_FRACTION,
    releaseNotes: [{ language: 'en-US', text: 'Notes' }],
  });
});

test('plan: the completed release stays, anything else is replaced', () => {
  const release = newRelease('1.4.1', 27, 'Notes');
  const current = {
    releases: [
      { name: '1.3.0', versionCodes: ['25'], status: 'completed' },
      { name: '1.4.0', versionCodes: ['26'], status: 'inProgress', userFraction: 0.1 },
    ],
  };
  const plan = planTrack(current, release);
  assert.deepEqual(plan.releases.map((r) => r.name), ['1.3.0', '1.4.1']);
  assert.deepEqual(plan.replaced.map((r) => r.name), ['1.4.0']);
  assert.deepEqual(planTrack({}, release).releases, [release]);
  assert.deepEqual(planTrack({ releases: [{ name: 'd', status: 'draft' }, { name: 'h', status: 'halted' }] }, release)
    .replaced.map((r) => r.name), ['d', 'h']);
});

test('read-back: a match has no differences; each field is checked', () => {
  const expected = newRelease('1.4.0', 26, 'Bugfixes\n\n– One');
  const track = (overrides) => ({
    track: 'production',
    releases: [{ name: '1.3.0', versionCodes: ['25'], status: 'completed' }, { ...expected, ...overrides }],
  });
  assert.deepEqual(differences(track({}), expected), []);
  // Play may return CRLF or a trailing newline; that's not a difference
  assert.deepEqual(differences(track({ releaseNotes: [{ language: 'en-US', text: 'Bugfixes\r\n\r\n– One\n' }] }), expected), []);
  assert.match(differences(track({ status: 'draft' }), expected)[0], /status is draft/);
  assert.match(differences(track({ userFraction: 0.1 }), expected)[0], /userFraction is 0.1/);
  assert.match(differences(track({ name: '1.4' }), expected)[0], /name is/);
  assert.match(differences(track({ releaseNotes: [{ language: 'en-US', text: 'Other' }] }), expected)[0], /release notes differ/);
  assert.match(differences(track({ releaseNotes: [] }), expected)[0], /release notes differ/);
  assert.match(differences({ track: 'production', releases: [] }, expected)[0], /release 26 is missing/);
});

test('commit failure: only Play refusing to send for review is retried; no refusal means it may have committed', () => {
  const failed = (status, message) => Object.assign(new Error(`POST /edits/1:commit → HTTP ${status}: ${message}`), { status });
  assert.equal(commitFailure(failed(400, 'Changes cannot be sent for review automatically. Please set the query '
    + 'parameter changesNotSentForReview to true.')), 'review');
  assert.equal(commitFailure(failed(403, 'The caller does not have permission')), 'refused');
  assert.equal(commitFailure(failed(500, 'Internal error')), 'unknown');
  assert.equal(commitFailure(failed(503, 'changesNotSentForReview')), 'unknown');
  assert.equal(commitFailure(new TypeError('fetch failed')), 'unknown');
});
