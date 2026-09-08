// Minimal GitHub REST + GraphQL helpers for the PR reviewer.
// REST is used for issue/inline comments; GraphQL is used to enumerate and RESOLVE
// review threads (there is no REST endpoint for resolving a review thread).

const REST = 'https://api.github.com';
const GQL = 'https://api.github.com/graphql';

function token() {
  const t = process.env.GITHUB_TOKEN;
  if (!t) throw new Error('GITHUB_TOKEN env var is required');
  return t;
}

export function repo() {
  const full = process.env.GITHUB_REPOSITORY; // "owner/name"
  if (!full) throw new Error('GITHUB_REPOSITORY env var is required');
  const [owner, name] = full.split('/');
  return { owner, name, full };
}

function headers(tok) {
  return {
    Authorization: `Bearer ${tok || token()}`,
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'Content-Type': 'application/json',
  };
}

// A stalled GitHub call should fail into the harness's degrade paths, not sit until the job timeout.
const API_TIMEOUT_MS = 30_000;

async function rest(method, path, body) {
  const url = path.startsWith('http') ? path : `${REST}${path}`;
  const res = await fetch(url, {
    method,
    headers: headers(),
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(API_TIMEOUT_MS),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`GitHub ${method} ${path} -> ${res.status}: ${text}`);
  }
  return res.status === 204 ? null : res.json();
}

async function graphql(queryStr, variables, tok) {
  const res = await fetch(GQL, {
    method: 'POST',
    headers: headers(tok),
    body: JSON.stringify({ query: queryStr, variables }),
    signal: AbortSignal.timeout(API_TIMEOUT_MS),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok || json.errors) {
    throw new Error(`GitHub GraphQL -> ${res.status}: ${JSON.stringify(json.errors || json)}`);
  }
  return json.data;
}

// ---------- Pull request metadata + diff (fetched by the harness so the agent needs no token) ----------

export async function getPullRequest(prNumber) {
  const { owner, name } = repo();
  const pr = await rest('GET', `/repos/${owner}/${name}/pulls/${prNumber}`);
  return { title: pr.title || '', body: pr.body || '' };
}

export async function fetchPullRequestDiff(prNumber) {
  const { owner, name } = repo();
  const res = await fetch(`${REST}/repos/${owner}/${name}/pulls/${prNumber}`, {
    headers: { ...headers(), Accept: 'application/vnd.github.diff' },
    signal: AbortSignal.timeout(API_TIMEOUT_MS),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`GitHub GET diff -> ${res.status}: ${text}`);
  }
  return res.text();
}

// ---------- Summary (issue-level) comments ----------

export async function listIssueComments(prNumber) {
  const { owner, name } = repo();
  const all = [];
  let page = 1;
  for (;;) {
    const batch = await rest(
      'GET',
      `/repos/${owner}/${name}/issues/${prNumber}/comments?per_page=100&page=${page}`,
    );
    if (!Array.isArray(batch) || batch.length === 0) break;
    all.push(...batch);
    if (batch.length < 100) break;
    page++;
  }
  return all;
}

export async function postIssueComment(prNumber, body) {
  const { owner, name } = repo();
  return rest('POST', `/repos/${owner}/${name}/issues/${prNumber}/comments`, { body });
}

export async function updateIssueComment(commentId, body) {
  const { owner, name } = repo();
  return rest('PATCH', `/repos/${owner}/${name}/issues/comments/${commentId}`, { body });
}

// ---------- Inline (review) comments ----------

export async function postInlineComment({ prNumber, commitId, path, line, body }) {
  const { owner, name } = repo();
  return rest('POST', `/repos/${owner}/${name}/pulls/${prNumber}/comments`, {
    body,
    commit_id: commitId,
    path,
    line,
    side: 'RIGHT',
  });
}

// ---------- Review threads (dedup source + resolve) ----------

// Every review thread on the PR: identity, resolution state, where it is anchored, and its full comment list
// (author login + association, so the harness can tell a maintainer's reply from anyone else's).
export async function listReviewThreads(prNumber) {
  const { owner, name } = repo();
  const threads = [];
  let cursor = null;
  for (;;) {
    const data = await graphql(
      `query($owner:String!,$name:String!,$number:Int!,$cursor:String){
        repository(owner:$owner,name:$name){
          pullRequest(number:$number){
            reviewThreads(first:100, after:$cursor){
              pageInfo{ hasNextPage endCursor }
              nodes{
                id
                isResolved
                path
                line
                originalLine
                comments(first:30){ nodes{ databaseId body author { login } authorAssociation createdAt } }
                last: comments(last:1){ nodes{ body } }
              }
            }
          }
        }
      }`,
      { owner, name, number: prNumber, cursor },
    );
    const conn = data.repository.pullRequest.reviewThreads;
    for (const node of conn.nodes) {
      const comments = (node.comments?.nodes || []).map((c) => ({
        id: c.databaseId ?? null,
        body: c.body || '',
        author: c.author?.login || '',
        association: c.authorAssociation || 'NONE',
        createdAt: c.createdAt || '',
      }));
      threads.push({
        id: node.id,
        isResolved: node.isResolved,
        path: node.path || '',
        // Distinct on purpose: `line` is null exactly when the thread is outdated, and `originalLine` then points
        // into the commit the finding was raised on — a stale anchor the caller must not present as current.
        line: node.line ?? null,
        originalLine: node.originalLine ?? null,
        comments,
        firstCommentId: comments[0]?.id ?? null,
        firstCommentBody: comments[0]?.body || '',
        firstCommentAuthor: comments[0]?.author || '',
        // From its own selection, not the capped list: a thread with >30 comments would otherwise report the 30th.
        lastCommentBody: node.last?.nodes?.[0]?.body || '',
      });
    }
    if (!conn.pageInfo.hasNextPage) break;
    cursor = conn.pageInfo.endCursor;
  }
  return threads;
}

// Reply inside an existing review thread (used to leave the auto-resolve marker).
export async function replyToReviewComment(prNumber, commentId, body) {
  const { owner, name } = repo();
  return rest('POST', `/repos/${owner}/${name}/pulls/${prNumber}/comments/${commentId}/replies`, { body });
}

export async function unresolveReviewThread(threadId) {
  const tok = process.env.REVIEW_RESOLVE_TOKEN || process.env.GITHUB_TOKEN;
  return graphql(
    `mutation($threadId:ID!){
      unresolveReviewThread(input:{threadId:$threadId}){ thread{ id isResolved } }
    }`,
    { threadId },
    tok,
  );
}

export async function resolveReviewThread(threadId) {
  // The default GITHUB_TOKEN (github-actions[bot]) is NOT allowed to resolve review threads
  // ("Resource not accessible by integration"), even with pull-requests: write. If a PAT / App
  // token is provided via REVIEW_RESOLVE_TOKEN, use it for the resolve mutation; otherwise fall
  // back to GITHUB_TOKEN (which will fail — threads then only show as GitHub's auto "Outdated").
  const tok = process.env.REVIEW_RESOLVE_TOKEN || process.env.GITHUB_TOKEN;
  return graphql(
    `mutation($threadId:ID!){
      resolveReviewThread(input:{threadId:$threadId}){ thread{ id isResolved } }
    }`,
    { threadId },
    tok,
  );
}
