#!/usr/bin/env bash
#
# Wrapper around `./gradlew` for publishing tasks that need Central Portal
# credentials and GPG signing material. Reads values from `local.properties`
# (which is gitignored) and exports them as `ORG_GRADLE_PROJECT_*` environment
# variables that Gradle exposes to the build as project properties — that
# being the only injection path that the com.vanniktech.maven.publish plugin
# (and Gradle's signing plugin) can read at the time it needs them.
#
# Required keys in local.properties:
#   centralUsername              Central Portal user-token username
#   centralPassword              Central Portal user-token password
#   signing.keyId                Last 8 chars of GPG key id
#   signing.password             GPG key passphrase
#   signing.secretKeyRingFile    Path (relative to repo root) to the
#                                ASCII-armored secret key (.asc)
#
# Usage:
#   scripts/publish.sh :client-library:publishToMavenCentral
#   scripts/publish.sh :client-library:publishAndReleaseToMavenCentral
#   scripts/publish.sh :client-library:publishToMavenLocal   # smoke-test

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOCAL_PROPS="$ROOT_DIR/local.properties"

if [ ! -f "$LOCAL_PROPS" ]; then
  echo "error: $LOCAL_PROPS not found" >&2
  exit 1
fi

# Read a key from local.properties. Returns empty string if absent.
read_prop() {
  awk -v key="$1" -F= '
    $0 ~ "^[[:space:]]*"key"[[:space:]]*=" {
      sub("^[^=]*=", "", $0)
      print $0
      exit
    }
  ' "$LOCAL_PROPS"
}

CENTRAL_USER="$(read_prop centralUsername)"
CENTRAL_PASS="$(read_prop centralPassword)"
SIGNING_KEY_ID="$(read_prop 'signing\.keyId')"
SIGNING_PASS="$(read_prop 'signing\.password')"
SIGNING_KEY_FILE="$(read_prop 'signing\.secretKeyRingFile')"

missing=()
[ -n "$CENTRAL_USER" ]    || missing+=("centralUsername")
[ -n "$CENTRAL_PASS" ]    || missing+=("centralPassword")
[ -n "$SIGNING_KEY_ID" ]  || missing+=("signing.keyId")
[ -n "$SIGNING_PASS" ]    || missing+=("signing.password")
[ -n "$SIGNING_KEY_FILE" ] || missing+=("signing.secretKeyRingFile")

if [ ${#missing[@]} -gt 0 ]; then
  echo "error: missing keys in local.properties: ${missing[*]}" >&2
  exit 1
fi

KEY_PATH="$ROOT_DIR/$SIGNING_KEY_FILE"
if [ ! -f "$KEY_PATH" ]; then
  echo "error: signing key file not found at $KEY_PATH" >&2
  exit 1
fi

export ORG_GRADLE_PROJECT_mavenCentralUsername="$CENTRAL_USER"
export ORG_GRADLE_PROJECT_mavenCentralPassword="$CENTRAL_PASS"
export ORG_GRADLE_PROJECT_signingInMemoryKeyId="$SIGNING_KEY_ID"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$SIGNING_PASS"
ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat "$KEY_PATH")"
export ORG_GRADLE_PROJECT_signingInMemoryKey

if [ -z "${JAVA_HOME:-}" ]; then
  ZULU17="/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home"
  if [ -d "$ZULU17" ]; then
    export JAVA_HOME="$ZULU17"
  fi
fi

exec "$ROOT_DIR/gradlew" "$@"
