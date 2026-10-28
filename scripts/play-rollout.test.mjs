// Unit tests for scripts/play-rollout.mjs (no network). Run with: node --test scripts/play-rollout.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  LADDER, describeTracks, main, mismatch, nextRung, parseArgs, planHalt, planStep, rolloutOf, serviceAccountJson,
} from './play-rollout.mjs';

const live = (code) => ({ name: '1.3.0', versionCodes: [String(code)], status: 'completed', releaseNotes: [{ language: 'en-US', text: 'Old' }] });
const rolling = (code, userFraction, status = 'inProgress') => ({
  name: '1.4.0', versionCodes: [String(code)], status, userFraction, releaseNotes: [{ language: 'en-US', text: 'New' }],
});
const CODES = ['26', '100016'];
const tracks = (fraction, { status, wearFraction = fraction } = {}) => [
  { track: 'production', releases: [live(25), rolling(26, fraction, status)] },
  { track: 'wear:production', releases: [live(100015), rolling(100016, wearFraction, status)] },
];

test('ladder: each rung goes to the next, and a rollout set by hand goes to the rung above it', () => {
  assert.deepEqual(LADDER.map(nextRung), [0.02, 0.05, 0.1, 0.25, 0.5, 1, null]);
  assert.equal(nextRung(0.03), 0.05);
  assert.equal(nextRung(0.3), 0.5);
  assert.equal(nextRung(0.1 + 1e-9), 0.25);
});

test('rollout: in progress or halted counts, completed and draft do not, two is an error', () => {
  assert.equal(rolloutOf({ track: 'production', releases: [live(25)] }), null);
  assert.equal(rolloutOf({ track: 'production', releases: [live(25), rolling(26, 0.05, 'halted')] }).status, 'halted');
  assert.equal(rolloutOf({ track: 'production', releases: [live(25), { ...rolling(26), status: 'draft' }] }), null);
  assert.throws(() => rolloutOf({ track: 'production', releases: [rolling(26, 0.01), rolling(27, 0.02)] }), /2 releases rolling out/);
});

test('status: each track with its rollout, its live release and the next rung', () => {
  assert.deepEqual(describeTracks(tracks(0.02))[0], {
    track: 'production',
    rollout: { name: '1.4.0', versionCodes: ['26'], status: 'inProgress', userFraction: 0.02 },
    live: { name: '1.3.0', versionCodes: ['25'] },
    next: 0.05,
  });
  assert.equal(describeTracks(tracks(0.05, { status: 'halted' }))[1].next, null);
  assert.equal(describeTracks([{ track: 'production', releases: [live(25)] }])[0].rollout, null);
});

test('step: both tracks move to the next rung, keeping the live release and the notes', () => {
  const changes = planStep(tracks(0.02), 0.02, CODES);
  assert.deepEqual(changes.map((c) => c.track), ['production', 'wear:production']);
  assert.deepEqual(changes[0].releases, [live(25), rolling(26, 0.05)]);
  assert.deepEqual(changes[1].expected, rolling(100016, 0.05));
});

test('step to 100%: the release completes and replaces the old one; a draft stays', () => {
  const draft = { name: '1.5.0', versionCodes: ['27'], status: 'draft' };
  const input = tracks(0.5);
  input[0].releases.push(draft);
  const [phone] = planStep(input, 0.5, CODES);
  const { userFraction, ...completed } = { ...rolling(26, 0.5), status: 'completed' };
  assert.equal(userFraction, 0.5);
  assert.deepEqual(phone.releases, [draft, completed]);
  assert.equal('userFraction' in phone.expected, false);
});

test('step refuses, changing nothing, unless both tracks roll out the expected build at --expect', () => {
  assert.throws(() => planStep(tracks(0.05), 0.02, CODES), /production: at 5%, not the expected 2%/);
  assert.throws(() => planStep(tracks(0.02, { wearFraction: 0.05 }), 0.02, CODES), /wear:production: at 5%/);
  assert.throws(() => planStep(tracks(0.02, { status: 'halted' }), 0.02, CODES), /a person resumes a halted rollout/);
  assert.throws(() => planStep([{ track: 'production', releases: [live(25)] }], 0.02, ['26']), /nothing is rolling out/);
  const mixed = tracks(0.02);
  mixed[1].releases[1].name = '1.4.1';
  assert.throws(() => planStep(mixed, 0.02, CODES), /different releases/);
});

test('step refuses a build it was not told about: a release submitted after status was read', () => {
  assert.throws(() => planStep(tracks(0.01), 0.01, ['27', '100016']),
    /production: rolling out 1.4.0 \(26\) inProgress at 1%, not the expected 27/);
  assert.throws(() => planStep(tracks(0.01), 0.01, ['26', '100017']), /wear:production: rolling out .*not the expected 100017/);
  assert.throws(() => planStep(tracks(0.01), 0.01), /one expected versionCode per track/);
  assert.throws(() => planStep(tracks(0.01), 0.01, ['26']), /one expected versionCode per track/);
});

test('arguments: anything unknown is refused, so a mistyped --dry-run never makes a real change', async () => {
  assert.deepEqual(parseArgs(['status']), { command: 'status', dryRun: false });
  assert.deepEqual(parseArgs(['step', '--expect', '0.02', '--expect-codes', '26,100016', '--dry-run']),
    { command: 'step', dryRun: true, expect: 0.02, codes: CODES });
  assert.deepEqual(parseArgs(['halt', '--dry-run']), { command: 'halt', dryRun: true });
  for (const typo of ['--dryrun', '--dry_run', '-n', 'dry-run']) {
    assert.throws(() => parseArgs(['step', '--expect', '0.02', '--expect-codes', '26,100016', typo]), /unknown argument/);
    assert.throws(() => parseArgs(['halt', typo]), /unknown argument/);
  }
  assert.throws(() => parseArgs(['status', '--dry-run']), /unknown argument "--dry-run" for status/);
  assert.throws(() => parseArgs(['halt', '--expect', '0.02']), /unknown argument "--expect" for halt/);
  assert.throws(() => parseArgs(['step', '--expect-codes', '26,100016']), /step needs --expect/);
  assert.throws(() => parseArgs(['step', '--expect', '1', '--expect-codes', '26,100016']), /step needs --expect/);
  assert.throws(() => parseArgs(['step', '--expect', '0.02']), /step needs --expect-codes/);
  assert.throws(() => parseArgs(['step', '--expect', '0.02', '--expect-codes', '26']), /step needs --expect-codes/);
  assert.throws(() => parseArgs(['step', '--expect', '0.02', '--expect-codes', '26,abc']), /step needs --expect-codes/);
  assert.throws(() => parseArgs(['step', '--expect']), /unknown argument "--expect"/);
  assert.throws(() => parseArgs(['resume']), /usage/);
  // Refused before signing in: no key is set here, so reaching Play would fail differently
  await assert.rejects(main(['step', '--expect', '0.02', '--expect-codes', '26,100016', '--dryrun']), /unknown argument "--dryrun"/);
});

test('halt: a rollout in progress halts at its fraction; halted or nothing rolling out is no change', () => {
  const [phone, wear] = planHalt(tracks(0.05));
  assert.deepEqual(phone.expected, rolling(26, 0.05, 'halted'));
  assert.deepEqual(wear.releases, [live(100015), rolling(100016, 0.05, 'halted')]);
  assert.deepEqual(planHalt(tracks(0.05, { status: 'halted' })), []);
  assert.deepEqual(planHalt([{ track: 'production', releases: [live(25)] }]), []);
});

test('read-back: status and fraction must match; a completed release has no fraction', () => {
  const [track] = tracks(0.05);
  assert.equal(mismatch(track, rolling(26, 0.05)), null);
  assert.match(mismatch(track, rolling(26, 0.1)), /Play has 1.4.0 \(26\) inProgress at 5%, expected .* at 10%/);
  assert.match(mismatch(track, rolling(27, 0.05)), /release 27 is missing/);
  const { userFraction, ...completed } = { ...rolling(26, 0.5), status: 'completed' };
  assert.equal(userFraction, 0.5);
  assert.equal(mismatch({ track: 'production', releases: [completed] }, completed), null);
  assert.match(mismatch(track, completed), /expected 1.4.0 \(26\) completed/);
});

test('key: the JSON, else the base64 one decoded, else an error', () => {
  assert.equal(serviceAccountJson({ PLAY_SERVICE_ACCOUNT_JSON: '{"a":1}', PLAY_SERVICE_ACCOUNT_JSON_B64: 'x' }), '{"a":1}');
  assert.equal(serviceAccountJson({ PLAY_SERVICE_ACCOUNT_JSON_B64: Buffer.from('{"b":2}').toString('base64') }), '{"b":2}');
  assert.throws(() => serviceAccountJson({}), /neither PLAY_SERVICE_ACCOUNT_JSON nor PLAY_SERVICE_ACCOUNT_JSON_B64/);
});
