#!/usr/bin/env bash
# Fail when R8 renamed or moved a class that another process resolves BY NAME.
#
# Usage: scripts/audit-mapping.sh <app|wear> [path/to/mapping.txt]
#        (default mapping: <module>/build/outputs/mapping/prodRelease/mapping.txt — run
#         `./gradlew :<module>:assembleProdRelease` or `bundleProdRelease` first)
#
# Why: Play REJECTED wear 100007 (1.1.2, the first minified build) for "missing ongoing activity".
# The watch's SystemUI deserializes OngoingActivity state from the media notification by class
# name in its own process; R8 had renamed that machinery, and nothing in the build, the unit tests
# or CI could see it — only Play review did. The keeps in wear/proguard-rules.pro fixed it; this
# script pins them (and the other by-name surfaces) so a future keep-rule regression, a dependency
# bump that drops consumer rules, or a shrinker change fails the PR instead of the release.
#
# What must map to itself (identity, `a.b.C -> a.b.C:`):
#   * every manifest component of the module (activity, service, receiver, provider, and the
#     class an activity-alias targets) — the OS starts them by name; AGP's generated rules keep
#     them, this pins that;
#   * Room's database class and its generated _Impl (looked up by name at runtime);
#   * wear only: everything under androidx.wear.ongoing.* and androidx.versionedparcelable.*.
# R8 synthetics ($$ExternalSyntheticLambda, $$Lambda, $-CC, -$$Nest$) are skipped: they are
# generated, never resolved by name. Add a surface here when you add a keep rule for a by-name
# contract (and vice versa).
set -euo pipefail

MODULE=${1:?usage: audit-mapping.sh <app|wear> [mapping.txt]}
MAPPING=${2:-$MODULE/build/outputs/mapping/prodRelease/mapping.txt}
[ -f "$MAPPING" ] || { echo "no mapping at $MAPPING — build the prodRelease variant first"; exit 2; }
# Resolved with a nullglob array rather than `ls`: under `set -euo pipefail` a failing `ls` inside
# the substitution would abort the script before the guard below could print its message.
shopt -s nullglob
MANIFESTS=("$MODULE"/build/intermediates/merged_manifests/prodRelease/*/AndroidManifest.xml)
shopt -u nullglob
MANIFEST=${MANIFESTS[0]:-}
[ -n "$MANIFEST" ] || { echo "no merged manifest under $MODULE/build/intermediates/merged_manifests/prodRelease — build the prodRelease variant first"; exit 2; }

python3 - "$MODULE" "$MAPPING" "$MANIFEST" <<'PY'
import re, sys, xml.etree.ElementTree as ET

module, mapping_path, manifest_path = sys.argv[1:4]
ANDROID = "{http://schemas.android.com/apk/res/android}"
SYNTHETIC = re.compile(r"\$\$ExternalSyntheticLambda|\$\$InternalSyntheticLambda|\$\$Lambda|\$-CC$|-\$\$Nest\$")

# --- what R8 did: class lines are `original -> obfuscated:` at column 0 ---------------------------
mapping = {}
for line in open(mapping_path, encoding="utf-8", errors="replace"):
    m = re.match(r"^(\S+) -> (\S+):$", line)
    if m:
        mapping[m.group(1)] = m.group(2)

# --- what must keep its name -----------------------------------------------------------------------
root = ET.parse(manifest_path).getroot()
package = root.get("package")
def qualify(name):
    if name.startswith("."): return package + name
    if "." not in name: return package + "." + name
    return name
required = set()
for tag in ("activity", "service", "receiver", "provider"):
    for node in root.iter(tag):
        name = node.get(ANDROID + "name")
        if name: required.add(qualify(name))
# An <activity-alias> is a component name with no class behind it (the alternate app icons);
# only the class it targets has to survive.
for node in root.iter("activity-alias"):
    target = node.get(ANDROID + "targetActivity")
    if target: required.add(qualify(target))
required.update({
    "com.tortugapower.audiobookplayer.database.AppDatabase",
    "com.tortugapower.audiobookplayer.database.AppDatabase_Impl",
})
prefixes = ["androidx.wear.ongoing.", "androidx.versionedparcelable."] if module == "wear" else []
for original in mapping:
    if any(original.startswith(p) for p in prefixes) and not SYNTHETIC.search(original):
        required.add(original)

# --- verdict ---------------------------------------------------------------------------------------
missing = sorted(c for c in required if c not in mapping)
renamed = sorted((c, mapping[c]) for c in required if c in mapping and mapping[c] != c)
unnamed = sum(1 for o, n in mapping.items() if o != n and "." not in n)
print(f"{module}: {len(mapping)} classes in mapping, {unnamed} renamed into the unnamed package, "
      f"{len(required)} by-name classes checked")
for c in missing:
    print(f"  MISSING  {c}  (not in the mapping: removed or never compiled — is it still a component?)")
for c, n in renamed:
    print(f"  RENAMED  {c} -> {n}")
if missing or renamed:
    print(f"FAIL: {len(missing) + len(renamed)} by-name class(es) would not resolve from another process")
    sys.exit(1)
print("OK: every by-name class maps to itself")
PY
