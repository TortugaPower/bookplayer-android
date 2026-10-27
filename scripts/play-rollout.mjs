#!/usr/bin/env node
// Moves a staged rollout on Google Play along: the release play-release.mjs submitted at 1% on `production` (phone)
// and `wear:production` (watch), both tracks together. Run by the daily rollout routine, or by hand.
//
//   node scripts/play-rollout.mjs status                           both tracks as JSON (what the routine reads)
//   node scripts/play-rollout.mjs step --expect 0.02 [--dry-run]   the next rung: 1 → 2 → 5 → 10 → 25 → 50 → 100%
//   node scripts/play-rollout.mjs halt [--dry-run]                 stops it reaching anyone new
//
// `step` moves only when both tracks are rolling out the same release at exactly --expect, so a change made in Play
// Console, or a second run, never skips a rung. At 100% the release becomes the track's completed release, replacing
// the old one. There is no resume: a person resumes a halted rollout in Play Console.
// Every change is read back inside the edit and the edit validated before committing, then read back after.
// --dry-run does the same and deletes the edit instead of committing it.
//
// Key: PLAY_SERVICE_ACCOUNT_JSON, or PLAY_SERVICE_ACCOUNT_JSON_B64 (base64, for settings that take one line only).

import { pathToFileURL } from 'node:url';
import { Play, TRACKS, accessToken, commitFailure, describeRelease, inEdit } from './play-release.mjs';

export const LADDER = [0.01, 0.02, 0.05, 0.1, 0.25, 0.5, 1];

const same = (a, b) => Math.abs(a - b) < 1e-6;
const percent = (fraction) => `${Math.round(Number(fraction) * 1000) / 10}%`;

/** The rung after `fraction`: the lowest one above it, so a rollout set to 3% by hand goes to 5%. */
export function nextRung(fraction) {
  return LADDER.find((rung) => rung > fraction && !same(rung, fraction)) ?? null;
}

/** The release rolling out on a track (in progress or halted), or null. */
export function rolloutOf(track) {
  const rolling = (track?.releases || []).filter((r) => r.status === 'inProgress' || r.status === 'halted');
  if (rolling.length > 1) {
    throw new Error(`${track.track}: ${rolling.length} releases rolling out (${rolling.map(describeRelease).join('; ')})`);
  }
  return rolling[0] || null;
}

/** What `status` prints for each track. */
export function describeTracks(tracks) {
  return tracks.map((track) => {
    const rollout = rolloutOf(track);
    const live = (track.releases || []).find((r) => r.status === 'completed');
    return {
      track: track.track,
      rollout: rollout && {
        name: rollout.name,
        versionCodes: rollout.versionCodes,
        status: rollout.status,
        userFraction: Number(rollout.userFraction),
      },
      live: live ? { name: live.name, versionCodes: live.versionCodes } : null,
      next: rollout?.status === 'inProgress' ? nextRung(Number(rollout.userFraction)) : null,
    };
  });
}

/**
 * One change per track for `step`: the rollout at the next rung, or completed at 100% (the old completed release
 * goes; anything else, like a draft, stays). Throws, changing nothing, unless both tracks roll out one release at
 * `expect`.
 */
export function planStep(tracks, expect) {
  const rollouts = tracks.map((track) => ({ track, rollout: rolloutOf(track) }));
  const problems = [];
  for (const { track, rollout } of rollouts) {
    if (!rollout) problems.push(`${track.track}: nothing is rolling out`);
    else if (rollout.status !== 'inProgress') {
      problems.push(`${track.track}: ${describeRelease(rollout)} (a person resumes a halted rollout in Play Console)`);
    } else if (!same(Number(rollout.userFraction), expect)) {
      problems.push(`${track.track}: at ${percent(rollout.userFraction)}, not the expected ${percent(expect)}`);
    }
  }
  if (!problems.length && new Set(rollouts.map(({ rollout }) => rollout.name)).size > 1) {
    problems.push(`the tracks roll out different releases: ${rollouts.map(({ rollout }) => describeRelease(rollout)).join('; ')}`);
  }
  if (problems.length) throw new Error(`not stepping:\n  ${problems.join('\n  ')}`);

  const to = nextRung(expect);
  return rollouts.map(({ track, rollout }) => {
    if (to === 1) {
      const { userFraction, ...rest } = rollout;
      const completed = { ...rest, status: 'completed' };
      return {
        track: track.track,
        releases: [...track.releases.filter((r) => r !== rollout && r.status !== 'completed'), completed],
        expected: completed,
        change: `${describeRelease(rollout)} → completed (100%)`,
      };
    }
    const stepped = { ...rollout, userFraction: to };
    return {
      track: track.track,
      releases: track.releases.map((r) => (r === rollout ? stepped : r)),
      expected: stepped,
      change: `${describeRelease(rollout)} → ${percent(to)}`,
    };
  });
}

/** One change per track rolling something out for `halt`; a track already halted, or with nothing rolling out, has none. */
export function planHalt(tracks) {
  return tracks.flatMap((track) => {
    const rollout = rolloutOf(track);
    if (rollout?.status !== 'inProgress') return [];
    const halted = { ...rollout, status: 'halted' };
    return [{
      track: track.track,
      releases: track.releases.map((r) => (r === rollout ? halted : r)),
      expected: halted,
      change: `${describeRelease(rollout)} → halted`,
    }];
  });
}

/** Why the release Play stored on `track` isn't `expected`, or null when it is. */
export function mismatch(track, expected) {
  const codes = JSON.stringify(expected.versionCodes);
  const stored = (track?.releases || []).find((r) => JSON.stringify(r.versionCodes) === codes);
  if (!stored) return `${track?.track}: release ${expected.versionCodes.join(', ')} is missing`;
  const fractionMatches = expected.status === 'completed'
    ? stored.userFraction === undefined
    : same(Number(stored.userFraction), expected.userFraction);
  if (stored.status === expected.status && fractionMatches) return null;
  return `${track.track}: Play has ${describeRelease(stored)}, expected ${describeRelease(expected)}`;
}

/** The service account key from the environment. */
export function serviceAccountJson(env = process.env) {
  if (env.PLAY_SERVICE_ACCOUNT_JSON) return env.PLAY_SERVICE_ACCOUNT_JSON;
  if (env.PLAY_SERVICE_ACCOUNT_JSON_B64) return Buffer.from(env.PLAY_SERVICE_ACCOUNT_JSON_B64, 'base64').toString('utf8');
  throw new Error('neither PLAY_SERVICE_ACCOUNT_JSON nor PLAY_SERVICE_ACCOUNT_JSON_B64 is set');
}

// MARK: - Play

// Progress goes to stderr; stdout carries only the result (the JSON for `status`)
function log(message) {
  console.error(message);
}

const readTracks = (play, edit) => Promise.all(TRACKS.map(({ track }) => play.getTrack(edit, track)));

/** Plans the change from the tracks as this edit sees them, makes it, and commits it unless it's a dry run. */
async function change(play, plan, { dryRun }) {
  return inEdit(play, async (edit, state) => {
    const changes = plan(await readTracks(play, edit));
    if (!changes.length) return changes;
    for (const c of changes) await play.putTrack(edit, c.track, c.releases);

    const wrong = [];
    for (const c of changes) wrong.push(mismatch(await play.getTrack(edit, c.track), c.expected));
    if (wrong.some(Boolean)) throw new Error(`read back inside the edit, nothing changed:\n  ${wrong.filter(Boolean).join('\n  ')}`);
    try {
      await play.validate(edit);
    } catch (error) {
      throw new Error(`validate, nothing changed: ${error.message}`);
    }
    if (dryRun) {
      log('dry run: Play validates the change; the edit is deleted, nothing changed');
      return changes;
    }

    try {
      await play.commit(edit, false);
    } catch (error) {
      const failure = commitFailure(error);
      if (failure === 'unknown') {
        throw new Error(`commit: ${error.message}\n  Play didn't refuse it, so it may have gone through: run status `
          + '(a step that went through makes the same step refuse, since the rollout is no longer at --expect)');
      }
      throw new Error(`commit refused, nothing changed: ${error.message}`
        + `${failure === 'review' ? '\n  Play has a rejected change on file: make this change in Play Console' : ''}`);
    }
    state.committed = true;
    return changes;
  });
}

export async function main(argv = process.argv.slice(2)) {
  const [command, ...rest] = argv;
  const dryRun = rest.includes('--dry-run');
  const expectAt = rest.indexOf('--expect');
  const expect = expectAt >= 0 ? Number(rest[expectAt + 1]) : undefined;
  const usage = 'usage: play-rollout.mjs status | step --expect <fraction> [--dry-run] | halt [--dry-run]';
  if (!['status', 'step', 'halt'].includes(command)) throw new Error(usage);
  if (command === 'step' && !(expect > 0 && expect < 1)) throw new Error(`step needs --expect, the current fraction (0.01 for 1%)\n${usage}`);

  const { token, email } = await accessToken(serviceAccountJson());
  const play = new Play(token);
  log(`Signed in as ${email}`);

  if (command === 'status') {
    const tracks = await inEdit(play, (edit) => readTracks(play, edit));
    console.log(JSON.stringify({ tracks: describeTracks(tracks) }, null, 2));
    return;
  }

  const plan = command === 'step' ? (tracks) => planStep(tracks, expect) : planHalt;
  const changes = await change(play, plan, { dryRun });
  if (!changes.length) {
    console.log('nothing to halt: no track has a rollout in progress');
    return;
  }
  const verb = dryRun ? 'would change' : 'changed';
  console.log(changes.map((c) => `${verb} ${c.track}: ${c.change}`).join('\n'));
  if (dryRun) return;

  // What Play stored, in a fresh edit
  const wrong = await inEdit(play, async (edit) => {
    const found = [];
    for (const c of changes) found.push(mismatch(await play.getTrack(edit, c.track), c.expected));
    return found.filter(Boolean);
  });
  if (wrong.length) throw new Error(`committed, but the read-back after committing differs:\n  ${wrong.join('\n  ')}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(`\n${error.message}`);
    process.exit(1);
  });
}
