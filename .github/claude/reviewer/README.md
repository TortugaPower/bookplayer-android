# The AI PR reviewer

Runs on every push to a PR against `main` or `develop` (`.github/workflows/claude-review.yml`), reviews the
diff with a Claude agent, and keeps the result as review comments on the PR. Advisory: the check is always
green, a human still merges.

`review-guide.md` (one directory up) is the reviewer's rubric — what to flag, at what severity, what to skip.
It is the file to edit to change *what* gets reviewed. Everything below is about the harness that runs it.

## What a round does

1. Fetches the PR and its diff (the agent gets no token; the diff is written to `RUNNER_TEMP`).
2. **Review pass** — the agent reads the diff and the checkout with read-only tools and returns JSON findings.
3. Identity. A finding's identity in the record is the thread it lives on, plus the id of the comment the harness
   created for it — a round that posts a comment cannot know its thread id (the listing was read first), so
   without the comment id the round after a post falls back to the marker in the body, and one maintainer edit of
   that body loses the finding. The prompt lists the findings still open from earlier pushes, and the agent may answer
   `same_as: <id>` to say "this is that one again" — identity **stated** rather than inferred. Where it says
   nothing, the fallback is a fingerprint, `sha1(file|line|severity)`, corroborated against what the thread
   actually says: that hash identifies a *location*, and two different findings at one location used to become
   one. A finding matched to a thread that does not already carry its text gets that text posted as a reply, so
   no decision about identity — the agent's or the harness's — can bury a finding's wording.
4. A finding whose identity already has a comment is left alone; a new one is posted inline; one that cannot be
   anchored (no such line in the diff, past the 25-comment cap, a refused post) is listed in the summary.
5. **Verification pass** — a second agent judges every still-open thread this round did *not* re-report against
   the current code: `fixed`, `present`, `not_applicable`, `accepted` (a maintainer said so), `insufficient`,
   or `duplicate` of a finding this push reports. **This is the only thing that closes a thread.** Absence
   closes nothing; an `error` closes only on evidence of a fix or a maintainer's own resolve.
6. Writes one summary comment, which carries a hidden state record (`<!-- bp-ai-review-state:… -->`) of what
   this round did: which thread carries which finding, what was closed and why. The next round reads it instead
   of re-deriving its own history from rendered comments.

## Running the tests

```
cd .github/claude/reviewer && npm ci --ignore-scripts && node --test test/
```

~223 tests, a minute or so, no network and no API key. CI runs exactly this before the review step, so a red
suite means no review ran (and the workflow says so on the PR).

**And mutate the DOUBLE, not only the code.** The fake GitHub answered a posted comment with the id of the
comment created *next* — off by one, for as long as it has existed, because nothing had ever read that value.
The first code that did read it mis-identified every thread, and the conservation law reported it as lost
findings. A double that lies is worse than one that refuses.

`test/workflow.test.mjs` reads both workflows. On `ci.yml`: that the harness suite runs there unconditionally
(the reviewer workflow skips drafts, forks and Dependabot, and `ci-scope.sh` calls this directory inert — so
without it a pull request touching only the harness got no tests at all), that the job running pull-request code
states its permissions, and that nothing in the scope step can fail the required check. On `claude-review.yml`
it does the budget arithmetic: every step bounded, the step
caps fitting inside the job cap with slack, the review step's cap looser than the harness's own budget (so
`review.mjs` is what ends that step, not the runner), the two failure notes mutually exclusive and gated on
`failure()` rather than `always()`, and — the drift-killer — every cap named in a comment matching the real
number. Three consecutive review rounds found bugs in that file, all of them arithmetic nobody could check.
**When a comment names a cap, write it as "the job's N" or "the review step's N"**, which is the form that test
reads.

`test/comments.test.mjs` checks that every identifier a comment names exists in the code, with an allowlist for
the ones deliberately naming deleted code or something external — each entry carrying its reason. This harness is
commented heavily on purpose, which makes a wrong comment expensive: it is what a maintainer reads before
touching the code. Five wrong ones have been found by review so far, and this catches the sharpest kind.

`test/ci-scope.test.mjs` runs `scripts/ci-scope.sh`, which decides whether the repo's *other* workflow runs the
Android build. It lives here because this is the only script-level test runner in the repo and this suite runs on
every pull request — and because that decision governs a required check, so a wrong answer hands a green `build`
to untested Kotlin.

`test/shell-allowlist.test.mjs` holds the unit tests — the tool gate, the record, the prompts, the budgets.
`test/round.test.mjs` runs whole rounds through `runReview({ agent })` with `fetch` stubbed and the model
faked, which is where composition bugs show up.

**When you change behaviour, mutate it.** The discipline this harness is held to: make the change, then break
it on purpose and check a test fails. Most of the bugs found in it were found that way, and most of them lived
in code that was already covered by a test that could not see them.

## Running it locally

```
DRY_RUN=1 \
ANTHROPIC_API_KEY=… GITHUB_TOKEN=$(gh auth token) \
GITHUB_REPOSITORY=TortugaPower/bookplayer-android PR_NUMBER=114 \
COMMIT=$(gh pr view 114 --json headRefOid --jq .headRefOid) BASE_REF=develop \
RUNNER_TEMP=/tmp/reviewer \
node .github/claude/reviewer/review.mjs
```

`DRY_RUN=1` reads GitHub for real (PR, diff, comments) and runs the real agent, then prints the findings and
the summary it *would* post. Every write path sits behind that flag, so nothing reaches the PR — including
`--setup-failed`, whose note is gated inside `appendNoteToSummary` so no caller can forget it (one did). Drop the flag
only against a PR you are happy to have commented on.

To exercise the plumbing without spending a model call, stub the agent as the round tests do:
`runReview({ agent: async () => ({ finalText: '```json\n{…}\n```', resultSubtype: 'success' }) })`.

## Knobs

| env | default | what it does |
| --- | --- | --- |
| `REVIEW_MODEL` | unset | Pins the model. Unset = newest Opus-tier id from the Models API, with a fallback list. |
| `REVIEW_DEADLINE_MS` | 12 min | The review pass's own clock. |
| `REVIEW_JOB_BUDGET_MS` | 18 min | Both passes plus setup. The review is capped by this minus the verify slice. |
| `REVIEW_VERIFY_BUDGET_MS` | 5 min | Reserved for the verification pass; under 60 s left, it is skipped and the summary says so. |
| `REVIEW_MAX_TURNS` | 40 in code, 200 in the workflow | Runaway guard only; the real bound is the deadline. |
| `REVIEW_MAX_OUTPUT_TOKENS` | 32,000 | Per model response. A finding list cut off mid-JSON is reported as a partial round, and closes nothing. |
| `DRY_RUN` | off | Read everything, write nothing. |
| `ACTIONS_STEP_DEBUG` | off | Raises the agent-output dump in the log from 4 KB to 20 KB. A public repo's log is public. |

Raising `REVIEW_DEADLINE_MS` or `REVIEW_JOB_BUDGET_MS` means raising `timeout-minutes` in the workflow with
them — both the job's and the review step's. The harness's clock has to be the tighter of the two: its budget is
measured from before the model lookup and the reconcile phase after it is unclocked (up to 25 posts plus a
resolve and a reply per closed thread), so a step cap set too close cancels the round mid-write. Every step is
bounded, because a job cancelled by ITS OWN timeout runs no `if: failure()` step at all — the note saying the
reviewer did not run would never fire. A step killed anyway (its cap, an OOM) is covered by the last step in the
workflow, which fires only when `review.mjs` did not manage to say anything itself.

## Tokens

The SDK version is **pinned exactly** (`0.3.261`, not `^0.3.261`), and that is a safety property rather than
tidiness: the agent's sandbox is configured entirely by SDK option *names* — `settingSources: []`,
`allowedTools: []`, `permissionMode: 'default'`, `canUseTool`, `env` — and every test stubs the agent seam, so a
release that renamed or stopped honouring one of them would pass the whole suite with the isolation silently
weakened. **Raising it means reading the options block in `agentQuery` against the SDK's current types**, which is
why the bump has to be an edit a human makes rather than a range that drifts.

- `ANTHROPIC_API_KEY` — repository secret. The agent's environment is built by allowlist, so neither token
  below is visible to it.
- `REVIEW_RESOLVE_TOKEN` — optional but load-bearing: the default `GITHUB_TOKEN` cannot resolve review threads
  ("Resource not accessible by integration"), so without it every close fails, the threads stay open, and the
  summary says "could not be resolved" on each one. A fine-grained PAT scoped to this repository with
  **Pull requests: read & write** is enough — a classic repo-scope token over-reaches, since this job runs
  PR-branch code. To rotate: create the PAT, update the repository secret, and update the backup copy in SSM
  (`/github/review-resolve-pat`, profile `bookplayer`, us-east-1) so a write-only GitHub secret is recoverable.

## Things worth knowing before changing it

- **Nothing closes a thread except a judgement.** Two earlier designs closed threads by resemblance (file +
  severity + a similarity score over the comment texts) and both retired live findings: two different findings
  in one file measure 0.889 against a 0.5 bar. If you are tempted again, the answer is a verdict from the
  verification pass, which reads the code.
- **A finding never leaves the PR silently, and `test/conservation.test.mjs` is where that is enforced.** It
  states the law rather than testing a mechanism, and fuzzes rounds against it against a GitHub whose state
  evolves — drifting lines, rewordings, collisions, edited bodies, human resolves, failed posts, and an agent
  that lies about `same_as`. It has caught two bugs the whole mutation-testing loop missed. When you change how
  identity or closing works, run it first; if it passes and you expected it to fail, your change probably does
  not do what you think. Its failure injections are where its blind spots have been: the thread read, the
  comment read, the inline post, the resolve, the reason-reply and the summary write can each be refused for a
  round. Every one of those was added after the round it could not see hid a real bug.
- **A close the harness cannot explain on the thread is not made.** The reply carrying the reason goes AFTER the
  resolve on purpose (without `REVIEW_RESOLVE_TOKEN` every resolve fails, and reply-first would claim "verified
  fixed" on every thread that stayed open), so a thread with no comment to reply to — GitHub can answer with an
  empty `first` selection — is judged, reported and left open instead. The two rejected alternatives: undoing the
  close flaps the thread on every push, and a row in the summary explains it for exactly one round, because the
  next round's summary replaces it.
- **Similarity may decide MATCHING, never CLOSING.** A wrong match costs an extra comment somebody can see; a
  wrong close costs a finding. Every use of `findingSimilarity` is on the first side of that line.
- **The record is the harness's memory, and every summary write replaces the comment it lives in.** Any path
  that writes a summary must carry a record — its own, or the one it read. Two bugs came from a path that
  wrote one without.
- **A summary that cannot be written is a fatal error, not a warning.** It is the round's only durable output:
  the findings that could not be posted inline live in it, and so does the record. Swallowing the failure let a
  round report findings, put none of them anywhere, and exit 0 — indistinguishable, on an advisory check, from
  a clean review. It throws now, and the job goes red. The one exception is a summary comment that has been
  *deleted* (404/410), where posting a new one is right; any other refusal must not post, because a second
  summary means two records.
- **The agent's Bash is a grammar, not an emulator.** `analyzeShell` accepts only what it can prove it has
  parsed exactly as bash would (the words it sees ARE the argv), and flags are allowlisted per command in full
  spelling, because `getopt_long` accepts any unambiguous prefix. Adding a command means adding its flags, and
  anything that follows symlinks, never returns, or takes filenames from a file stays out.
- **Everything the model writes is untrusted at the write boundary.** `redact()` runs on every body, reply and
  record field; `neutralizeMarkup` stops model text from opening an HTML comment, which is what keeps a
  finding from forging a state record or a fingerprint marker. The same applies to the answer itself: the review's
  result is taken from the terminal fenced block the output contract mandates, so a result-shaped example quoted
  inside a finding — this file's own guide contains one — cannot be adopted as the round's answer.
