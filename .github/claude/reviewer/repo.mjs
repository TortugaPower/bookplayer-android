// The per-repository half of the sandbox. Everything else in this directory is portable; this file and
// `../review-guide.md` are the two that change when the harness is copied to another repository. A copy that
// keeps these lists gets rules that match nothing of its own and no rule naming its secret files — so review
// both when you port.

// Files in the checkout that hold credentials even though they are gitignored: no step materialises them today,
// but the moment a build step writes one from Actions secrets the agent could otherwise read it and quote a value
// `redact` has no pattern for (a base URL, a client id). Matched by name wherever they appear in a path or a
// command; `.example`/`.template`/`.sample` copies of them stay readable.
export const REPO_SECRET_FILES = ['local.properties', 'keystore.properties', 'google-services.json'];

// Secret SHAPES this repository's code and configuration can contain, applied by `redact` after the generic ones
// (Anthropic keys, GitHub tokens, PEM private keys). Each entry carries the example(s) that prove it and a
// look-alike that must pass untouched: the harness's own test runs both, so a shape cannot be listed without
// working and cannot eat prose. (Keystore passwords are deliberately not pattern-matched: they live only in a
// gitignored keystore.properties and in Actions secrets, and no useful pattern exists that would not mangle prose.)
export const REPO_SECRET_SHAPES = [
  {
    // Any sentry.io host, not only the modern `o<org>.ingest[.<region>].sentry.io`: the legacy
    // `https://<32 hex>@sentry.io/<id>` form is still valid and still what older projects carry, and it was
    // passing through unredacted. Redaction is the boundary that catches what the path rules cannot, so it is
    // widened rather than kept precise.
    pattern: /https:\/\/[0-9a-f]{16,}(?::[0-9a-f]+)?@[\w.-]*sentry\.io\/\d+/gi,
    replacement: 'https://[redacted]@sentry.io/[redacted]',
    example: [
      'dsn https://0123456789abcdef0123456789abcdef@o12345.ingest.sentry.io/6789 set', // the modern host
      'https://0123456789abcdef0123456789abcdef@sentry.io/1234', // legacy, no secret
      'https://0123456789abcdef0123456789abcdef:fedcba9876543210@sentry.io/1234', // legacy key:secret@
    ],
    keeps: 'see sentry.io/docs and o1.ingest.sentry.io for setup',
  },
  {
    // A recursive grep can reach the CONTENTS of a secret file even though naming it is denied, so the post
    // boundary has to catch what the path rule cannot: an OAuth client id is the one value in there with a shape
    // worth matching. (A base URL is not a secret shape; the path rule remains the defence for those.)
    pattern: /\b\d{6,}-[a-z0-9]{20,}\.apps\.googleusercontent\.com\b/g,
    replacement: '[redacted client id]',
    example: 'id 123456789012-abcdefghijklmnopqrstuvwxyz012345.apps.googleusercontent.com set',
    keeps: 'the googleusercontent client id stays',
  },
  {
    // RevenueCat and store keys.
    pattern: /\b(goog|appl|amzn|strp|rcb)_[A-Za-z0-9]{20,}\b/g,
    replacement: '[redacted]',
    example: 'rc goog_' + 'A'.repeat(24) + ' set',
    keeps: 'a data-sync-task-uuid identifier',
  },
];
