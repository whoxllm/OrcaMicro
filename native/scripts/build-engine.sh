#!/usr/bin/env bash
# Builds liborcamicro_engine.so (OrcaSlicer's libslic3r plus the JNI bridge) against the
# dependencies from build-deps.sh, strips it and copies it into an Android jniLibs folder.
#
# usage: build-engine.sh <jniLibs dir>      e.g. build-engine.sh ../../app/src/main/jniLibs
source "$(dirname "$0")/common.sh"
require_ndk

out_dir=${1:?usage: build-engine.sh <jniLibs dir>}
[ -f "$ORCAMICRO_PREFIX/.stamps/opencv" ] || die "Dependencies missing in $ORCAMICRO_PREFIX; run build-deps.sh first"
git -C "$ORCA_DIR" apply --reverse --check "$NATIVE_DIR/patches/orcaslicer-$ORCA_TAG-android-engine.patch" 2>/dev/null ||
    die "OrcaSlicer at $ORCA_DIR is not patched; run fetch-orca.sh first"

launcher=()
if command -v ccache > /dev/null; then
    launcher=(-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache)
fi

build="$BUILD_DIR/engine"
log "Configuring the engine"
cmake -S "$ORCA_DIR" -B "$build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_PREFIX_PATH="$ORCAMICRO_PREFIX" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DORCAMICRO_ENGINE=ON \
    -DORCAMICRO_DIR="$NATIVE_DIR" \
    -DSLIC3R_GUI=OFF \
    -DSLIC3R_STATIC=ON \
    -DSLIC3R_PCH=OFF \
    -DSLIC3R_ENC_CHECK=OFF \
    -DBUILD_TESTS=OFF \
    -DOpenCV_DIR="$ORCAMICRO_PREFIX/sdk/native/jni" \
    "${launcher[@]}"

log "Building the engine"
# -k 0: report every compile error of a run, not only the first. Warnings bury the errors in
# the full log, so a failed build ends with just the error lines.
build_log="$build/build.log"
if ! cmake --build "$build" --target orcamicro_engine -j "$JOBS" -- -k 0 2>&1 | tee "$build_log"; then
    echo "==> Errors:" >&2
    grep -E "error:|FAILED:|undefined (reference|symbol)" "$build_log" | grep -v "^ccache " | head -100 >&2 || true
    die "The engine did not build"
fi

library="$build/orcamicro-bridge/liborcamicro_engine.so"
[ -f "$library" ] || die "Build finished without $library"
mkdir -p "$out_dir/arm64-v8a"
"$NDK_BIN/llvm-strip" --strip-unneeded -o "$out_dir/arm64-v8a/liborcamicro_engine.so" "$library"
log "Engine: $out_dir/arm64-v8a/liborcamicro_engine.so ($(du -h "$out_dir/arm64-v8a/liborcamicro_engine.so" | cut -f1))"
