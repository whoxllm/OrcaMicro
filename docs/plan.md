# OrcaMicro implementation plan

Design: [design.md](design.md)

Each milestone ends with its own check.

## 1. Native engine (built in CI)

- [x] Pin OrcaSlicer v2.4.2; write the headless Android engine patch (root, src and libslic3r
      CMake files, plus SLA hollowing without OpenVDB).
- [x] MD5 shim for OpenSSL, verified against `md5sum`.
- [x] Android arm64 toolchain wrapper (API 28, c++_static, 16 KB pages, libc++ compatibility flags).
- [x] `build-deps.sh` with per-library stamps; `build-engine.sh`.
- [x] JNI bridge mirroring the CLI's config assembly and slicing steps.
- [x] Desktop build of the same engine (`orcamicro_cli`): the smoke-test models, every setting
      the app edits, and a cube on all 953 catalog printers (946 slice; the other 7 carry vendor
      G-code that OrcaSlicer 2.4.2 rejects too). A clang 18 + libc++ build of libslic3r stands in
      for the NDK toolchain.
- [x] Dependencies cross-compiled and cached in CI.
- [x] Engine workflow green: `liborcamicro_engine.so`.

Check: `engine-arm64-v8a` artifact exists, and the smoke test (milestone 4) passes.

## 2. Core module

- [x] STL io, placement, bounds.
- [x] Catalog builder and preset resolver; sweep over every Orca printer.
- [x] Settings model, preset editor, bed type handling, engine protocol.
- [x] G-code parser with Orca's tags and statistics.

Check: `./gradlew :core:test -PcoreOnly -PorcaResourcesDir=...`, all 27 tests passing.

## 3. App

- [x] Gradle module, asset packing and catalog generation.
- [x] Engine service in the `:engine` process and its client.
- [x] GL plate and toolpath renderers, gestures.
- [x] Screens: plate, presets, settings, preview, about.
- [x] Compiles in CI (`Build APK` workflow).

## 4. End to end

- [x] Instrumented smoke test: cube on generic Marlin, plus normal and tree supports on the Ender-3 V3 SE,
      and one slice through the engine service in the `:engine` process.
- [x] Smoke test green on the x86_64 API 34 emulator with the arm64 engine (5 tests, 52 s).
- [x] `OrcaMicro-apk` artifact and the rolling `latest` pre-release published.
