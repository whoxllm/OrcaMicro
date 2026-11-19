#!/usr/bin/env bash
# Downloads the OrcaMicro engine built from exactly these native/ sources, so the app can be
# built without compiling OrcaSlicer (which takes hours).
#
# The Native engine workflow publishes every engine it builds as a pre-release tagged
# engine-<first 12 characters of `git rev-parse HEAD:native`>. That git hash covers every file in
# native/, so a changed engine source gets a different tag and never picks up a stale engine.
#
# Usage: scripts/fetch-prebuilt-engine.sh [output dir]   (default: app/src/main/jniLibs)
# ORCAMICRO_ENGINE_REPO overrides the repository the release is read from (owner/name).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
out="${1:-$root/app/src/main/jniLibs}"
repo="${ORCAMICRO_ENGINE_REPO:-whoxllm/OrcaMicro}"

if [ -n "$(git status --porcelain -- native)" ]; then
  echo "native/ has uncommitted changes, so no published engine matches it. Build the engine instead (docs/BUILDING.md)." >&2
  exit 1
fi
tag="engine-$(git rev-parse HEAD:native | cut -c1-12)"
base="https://github.com/$repo/releases/download/$tag"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
if ! curl --fail --silent --show-error --location --retry 2 "$base/engine-arm64-v8a.zip" -o "$work/engine.zip"; then
  echo "No published engine for these native sources ($tag in $repo). Run the Native engine workflow or build it locally (docs/BUILDING.md)." >&2
  exit 1
fi
curl --fail --silent --show-error --location --retry 2 "$base/engine-arm64-v8a.zip.sha256" -o "$work/engine.sha256"
printf '%s  %s\n' "$(cut -d' ' -f1 "$work/engine.sha256")" "$work/engine.zip" | sha256sum --check --quiet -
mkdir -p "$out"
unzip -q -o "$work/engine.zip" -d "$out"
echo "Engine $tag from $repo installed in $out"
