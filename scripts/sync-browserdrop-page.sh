#!/usr/bin/env bash
# Copies the upload page (index.html, app.css, app.js) from SwiftBrowserDrop into :browserdrop's assets, so
# Android serves exactly the page iOS does. Usage: scripts/sync-browserdrop-page.sh <tag or commit>
# The ref it was copied from is recorded in browserdrop/PAGE_SOURCE (outside the assets: it isn't shipped).
set -euo pipefail

ref="${1:?usage: $0 <SwiftBrowserDrop tag or commit>}"
repo="https://github.com/TortugaPower/SwiftBrowserDrop.git"
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/browserdrop/src/main/assets/browserdrop/page"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
git clone --quiet "$repo" "$work"
commit="$(git -C "$work" rev-parse --verify "$ref^{commit}")"

for file in index.html app.css app.js; do
  git -C "$work" show "$commit:Sources/BrowserDrop/Resources/Page/$file" > "$dest/$file"
done
printf '%s\n%s (%s)\n' "$repo" "$ref" "$commit" > "$root/browserdrop/PAGE_SOURCE"
echo "Copied the page from $ref ($commit)"
