#!/usr/bin/env node
// Moves a staged rollout on Google Play along: the release play-release.mjs submitted at 1% on `production` (phone)
// and `wear:production` (watch), both tracks together. Run by the daily rollout routine, or by hand.
//
//   node scripts/play-rollout.mjs status        both tracks as JSON (what the routine reads)
//   node scripts/play-rollout.mjs step --expect 0.02 --expect-codes 26,100016 [--dry-run]
//                                               the next rung: 1 → 2 → 5 → 10 → 25 → 50 → 100%
//   node scripts/play-rollout.mjs halt [--dry-run]   stops it reaching anyone new
//
// `step` moves only when both tracks are rolling out the build it was told about (--expect-codes: phone, then watch)
// at exactly --expect, so a change made in Play Console, a release submitted meanwhile, or a second run never moves
// the wrong build or skips a rung. At 100% the release becomes the track's completed release, replacing the old one.
// There is no resume: a person resumes a halted rollout in Play Console. Any argument it doesn't know is refused.
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
 * goes; anything else, like a draft, stays). Throws, changing nothing, unless every track rolls out the build in
 * `codes` (one versionCode per track, in order) at `expect`, all of one release.
 */
export function planStep(tracks, expect, codes) {
  if (codes?.length !== tracks.length) throw new Error(`step needs one expected versionCode per track (${tracks.length})`);
  const rollouts = tracks.map((track) => ({ track, rollout: rolloutOf(track) }));
  const problems = [];
  rollouts.forEach(({ track, rollout }, i) => {
    if (!rollout) problems.push(`${track.track}: nothing is rolling out`);
    else if (JSON.stringify(rollout.versionCodes) !== JSON.stringify([String(codes[i])])) {
      problems.push(`${track.track}: rolling out ${describeRelease(rollout)}, not the expected ${codes[i]}`);
    } else if (rollout.status !== 'inProgress') {
      problems.push(`${track.track}: ${describeRelease(rollout)} (a person resumes a halted rollout in Play Console)`);
    } else if (!same(Number(rollout.userFraction), expect)) {
      problems.push(`${track.track}: at ${percent(rollout.userFraction)}, not the expected ${percent(expect)}`);
    }
  });
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

const USAGE = 'usage: play-rollout.mjs status\n'
  + '       play-rollout.mjs step --expect <fraction> --expect-codes <phone code>,<watch code> [--dry-run]\n'
  + '       play-rollout.mjs halt [--dry-run]';

/** The command line, refusing anything it doesn't know: a mistyped --dry-run must never make a real change. */
export function parseArgs(argv) {
  const [command, ...rest] = argv;
  if (!['status', 'step', 'halt'].includes(command)) throw new Error(USAGE);
  const options = { command, dryRun: false };
  for (let i = 0; i < rest.length; i += 1) {
    const arg = rest[i];
    if (arg === '--dry-run' && command !== 'status') options.dryRun = true;
    else if (arg === '--expect' && command === 'step' && i + 1 < rest.length) options.expect = Number(rest[++i]);
    else if (arg === '--expect-codes' && command === 'step' && i + 1 < rest.length) {
      options.codes = rest[++i].split(',').map((code) => code.trim());
    } else throw new Error(`unknown argument ${JSON.stringify(arg)} for ${command}\n${USAGE}`);
  }
  if (command === 'step') {
    if (!(options.expect > 0 && options.expect < 1)) {
      throw new Error(`step needs --expect, the current fraction (0.01 for 1%)\n${USAGE}`);
    }
    if (options.codes?.length !== TRACKS.length || !options.codes.every((code) => /^\d+$/.test(code))) {
      throw new Error(`step needs --expect-codes, the versionCodes rolling out (phone,watch)\n${USAGE}`);
    }
  }
  return options;
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
  const { command, dryRun, expect, codes } = parseArgs(argv);

  const { token, email } = await accessToken(serviceAccountJson());
  const play = new Play(token);
  log(`Signed in as ${email}`);

  if (command === 'status') {
    const tracks = await inEdit(play, (edit) => readTracks(play, edit));
    console.log(JSON.stringify({ tracks: describeTracks(tracks) }, null, 2));
    return;
  }

  const plan = command === 'step' ? (tracks) => planStep(tracks, expect, codes) : planHalt;
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
