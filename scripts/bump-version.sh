#!/usr/bin/env bash
#
# Bump the published library version.
#
# The version is defined once at the top of client-library/build.gradle.kts —
# the Gradle coordinates, the `version` field and the VERSION_NAME
# buildConfigField all derive from it — and is echoed in the README
# installation snippet. This script updates both and prints the resulting
# diff. It does not stage, commit, tag or publish anything.
#
# Usage:
#   scripts/bump-version.sh 1.3.0
#   scripts/bump-version.sh 1.4.0-alpha01
#   scripts/bump-version.sh --current     # print the current version and exit

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

GRADLE_FILE="$ROOT_DIR/client-library/build.gradle.kts"
README_FILE="$ROOT_DIR/README.md"

# Fully anchored patterns. Being this specific is deliberate: it guarantees we
# can never rewrite an unrelated version, such as `junitVersion` in
# gradle/libs.versions.toml or the literal `X.Y.Z` placeholder in RELEASING.md.
GRADLE_RE='^val libraryVersion = "[^"]+"$'
README_RE="^implementation 'com\.vonage:client-library:[^']+'\$"

die() { echo "error: $*" >&2; exit 1; }

current_version() {
  perl -ne 'print "$1\n" if /^val libraryVersion = "([^"]+)"$/' "$GRADLE_FILE"
}

[ -f "$GRADLE_FILE" ] || die "not found: $GRADLE_FILE"
[ -f "$README_FILE" ] || die "not found: $README_FILE"

if [ "${1:-}" = "--current" ]; then
  current_version
  exit 0
fi

NEW_VERSION="${1:-}"
[ -n "$NEW_VERSION" ] ||
  die "usage: $(basename "$0") <version>   e.g. 1.3.0 or 1.4.0-alpha01"

# semver core with an optional prerelease suffix
printf '%s' "$NEW_VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$' ||
  die "'$NEW_VERSION' is not a valid version (expected MAJOR.MINOR.PATCH[-suffix])"

# Each anchor must match exactly once. If a file has been restructured we want a
# loud failure rather than a silent no-op that ships the wrong version.
require_one_match() {
  local file="$1" regex="$2" label="$3" count
  count="$(grep -Ec "$regex" "$file" || true)"
  [ "$count" -eq 1 ] ||
    die "expected exactly 1 $label line in ${file#"$ROOT_DIR/"}, found $count"
}

require_one_match "$GRADLE_FILE" "$GRADLE_RE" "libraryVersion"
require_one_match "$README_FILE" "$README_RE" "installation snippet"

OLD_VERSION="$(current_version)"
[ "$OLD_VERSION" != "$NEW_VERSION" ] || die "already at $NEW_VERSION"

NEW_VERSION="$NEW_VERSION" perl -pi -e \
  's/^val libraryVersion = "[^"]+"$/val libraryVersion = "$ENV{NEW_VERSION}"/' \
  "$GRADLE_FILE"

NEW_VERSION="$NEW_VERSION" perl -pi -e \
  "s/^implementation 'com\\.vonage:client-library:[^']+'\$/implementation 'com.vonage:client-library:\$ENV{NEW_VERSION}'/" \
  "$README_FILE"

# Confirm the intended edits actually landed before reporting success.
ACTUAL="$(current_version)"
[ "$ACTUAL" = "$NEW_VERSION" ] ||
  die "bump failed: $GRADLE_FILE reports '$ACTUAL'"
grep -Fqx "implementation 'com.vonage:client-library:$NEW_VERSION'" "$README_FILE" ||
  die "bump failed: README installation snippet was not updated"

echo "bumped $OLD_VERSION -> $NEW_VERSION"
echo
git -C "$ROOT_DIR" --no-pager diff -- "$GRADLE_FILE" "$README_FILE"
