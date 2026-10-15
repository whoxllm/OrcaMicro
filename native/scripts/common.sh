#!/usr/bin/env bash
# Settings shared by the OrcaMicro native build scripts. Source it, don't run it.
set -euo pipefail

NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../orca.env
source "$NATIVE_DIR/orca.env"

WORK_DIR="${ORCAMICRO_WORK:-$NATIVE_DIR/build}"
export ORCAMICRO_PREFIX="${ORCAMICRO_PREFIX:-$WORK_DIR/prefix}"
ORCA_DIR="${ORCAMICRO_ORCA_DIR:-$WORK_DIR/orcaslicer}"
DOWNLOAD_DIR="${ORCAMICRO_DOWNLOADS:-$WORK_DIR/downloads}"
SOURCE_DIR="$WORK_DIR/src"
BUILD_DIR="$WORK_DIR/build"
TOOLCHAIN_FILE="$NATIVE_DIR/cmake/android-arm64.toolchain.cmake"
ANDROID_API=28
JOBS="${ORCAMICRO_JOBS:-$(nproc)}"

log() { echo "==> $*" >&2; }
die() { echo "error: $*" >&2; exit 1; }

require_ndk() {
    [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -f "$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" ] ||
        die "Set ANDROID_NDK_ROOT to an Android NDK (r27 or newer)"
    NDK_BIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
    export ANDROID_NDK_ROOT
}
