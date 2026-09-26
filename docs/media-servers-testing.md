# Media servers: device testing

The unit tests cover the flow's decisions against fakes and MockWebServer. What they cannot cover is a
real Jellyfin / AudiobookShelf answering, a real browser running the SSO leg, and a reverse proxy in
front of either — so each connection-flow change also gets a pass on a real server before it ships.

## What you need

- A Jellyfin server (default port `8096`) with **Quick Connect enabled** (Dashboard → General →
  Quick Connect). The app polls `/QuickConnect/Connect` every 5 s for up to 200 polls, so approving the
  code from another signed-in Jellyfin client within that window completes the sign-in.
- An AudiobookShelf server (default port `13378`) with an **OpenID provider** configured
  (Settings → Authentication → OpenID Connect). Any OIDC IdP works; a passkey-capable one (e.g. Pocket
  ID) exercises the same Auth Tab path a user with hardware keys will hit. The redirect URI ABS
  registers for the mobile flow is `audiobookshelf://oauth`; the app never changes it.
- A phone with **Chrome 137+** (or any Custom Tabs provider that declares Auth Tab support) for SSO.
  Without one the SSO button must NOT appear — that is the expected behavior, not a bug. Quick Connect
  and password sign-in have no browser requirement.
- Optionally a reverse proxy that requires custom headers (e.g. a Cloudflare Access-style gate keyed
  on `CF-Access-Client-Id` / `CF-Access-Client-Secret`) to exercise the headers editor end to end.

Point a `devDebug` build at the servers over plain http on the LAN, or over https through a tunnel /
proxy. Only https unlocks SSO; over http the method screen must show password only (an SSO-only server
over http is blocked with the "requires https" error).

## The pass

Run each on a fresh install (no saved servers), then again with the server already saved.

**Address screen**
1. Typing a full URL into the host field (`http://host:8096/jf`) splits it into scheme / host+path /
   port; typing plain text stays verbatim (no auto-brackets, no trailing-slash edits).
2. An IPv6 literal is bracketed in the assembled URL shown under the fields; a hostname is not.
3. A header row with an empty value, an `Authorization` key, or a non-ASCII name is struck through once
   the row loses focus; a duplicate key strikes the earlier row. The struck rows are not sent.

**Connect + routing**
4. Jellyfin with Quick Connect on → method screen (Password + Quick Connect). Off → password screen.
5. ABS with OIDC on, over https, capable browser → method screen with the server's own button text.
   Same server over http → password screen only. Same server on a phone without Auth Tab → password only.
6. A wrong port / unreachable host → a network error on the address screen; the fields keep their values.

**Sign-in paths**
7. Password: wrong credentials → "unauthorized" error, stays on the password screen. Right ones → the
   sheet closes and the server appears in the list with its real name.
8. Quick Connect: the code sheet shows a 6-character code; tapping it copies; approving from another
   client signs in; Cancel stops polling; letting it time out shows the timeout error.
9. SSO: the Auth Tab opens on the IdP (not on the ABS page); completing the login returns to the app and
   signs in; the system back gesture inside the tab cancels silently (no error). Adding a second ABS
   account on the same IdP starts a fresh (ephemeral) session rather than reusing the first login.

**Saved servers**
10. Connection Details from the list and from inside a library show name, URL, username, and every
    custom header; tapping the name renames the connection (persisted, shown in the library title).
11. Log out from either place deletes the connection; from inside a library it also leaves the library.
12. Revoke the token server-side, then open the library → the "sign in again" alert; Sign In opens the
    flow prefilled at the address step; signing in resumes the library with the same selected library.
13. Stop the server, then open the library → the error alert with Retry / Cancel (no Connection Details:
    a saved connection is rename-only, so the sheet has nothing that fixes a load failure); start it
    again and Retry loads. With items already showing, a failed next page is an alert with OK.
