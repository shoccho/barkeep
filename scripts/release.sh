#!/usr/bin/env bash
# Build the signed release APK and publish it as a GitHub release.
#
#   scripts/release.sh v1.2 "Release notes…"
#
# The asset is always named Barkeep.apk so the website's link
#   https://github.com/shoccho/barkeep/releases/latest/download/Barkeep.apk
# keeps pointing at the newest build.
#
# Needs keystore.properties (see app/build.gradle.kts) and gh logged in to the
# account that owns the repo. If gh's active account is a different one, run:
#   GH_TOKEN="$(gh auth token --user shoccho)" scripts/release.sh v1.2
set -euo pipefail

TAG="${1:?usage: scripts/release.sh <tag> [notes]}"
NOTES="${2:-}"
REPO="shoccho/barkeep"
cd "$(dirname "$0")/.."

[[ -f keystore.properties ]] || { echo "keystore.properties is missing — the APK would be signed with the debug key." >&2; exit 1; }

if [[ -z "${JAVA_HOME:-}" && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

./gradlew --quiet testDebugUnitTest assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/Barkeep.apk
shasum -a 256 dist/Barkeep.apk

if [[ -n "$NOTES" ]]; then NOTE_ARGS=(--notes "$NOTES"); else NOTE_ARGS=(--generate-notes); fi
gh release create "$TAG" dist/Barkeep.apk --repo "$REPO" --title "Barkeep $TAG" "${NOTE_ARGS[@]}"
