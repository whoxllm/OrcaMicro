#!/usr/bin/env bash
# Clones the pinned OrcaSlicer release and applies the OrcaMicro engine patch.
#
# usage: fetch-orca.sh [--profiles-only]
#   --profiles-only  sparse checkout of resources/ only (what the APK build needs)
source "$(dirname "$0")/common.sh"

profiles_only=false
[ "${1:-}" = "--profiles-only" ] && profiles_only=true

if [ ! -d "$ORCA_DIR/.git" ]; then
    log "Cloning OrcaSlicer $ORCA_TAG"
    mkdir -p "$(dirname "$ORCA_DIR")"
    if $profiles_only; then
        git clone --quiet --depth 1 --branch "$ORCA_TAG" --filter=blob:none --sparse "$ORCA_REPO" "$ORCA_DIR"
        git -C "$ORCA_DIR" sparse-checkout set resources/profiles resources/info resources/flush
    else
        git clone --quiet --depth 1 --branch "$ORCA_TAG" "$ORCA_REPO" "$ORCA_DIR"
    fi
fi

head="$(git -C "$ORCA_DIR" rev-parse HEAD)"
[ "$head" = "$ORCA_COMMIT" ] || die "OrcaSlicer checkout is at $head, expected $ORCA_COMMIT ($ORCA_TAG)"

if ! $profiles_only; then
    patch_file="$NATIVE_DIR/patches/orcaslicer-$ORCA_TAG-android-engine.patch"
    if git -C "$ORCA_DIR" apply --reverse --check "$patch_file" 2>/dev/null; then
        log "Engine patch already applied"
    else
        log "Applying $(basename "$patch_file")"
        git -C "$ORCA_DIR" apply "$patch_file"
    fi
fi
log "OrcaSlicer ready at $ORCA_DIR"
