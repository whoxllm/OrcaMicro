# OrcaMicro

A 3D-print slicer for Android that runs [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer)'s
slicing engine on the phone itself. Open an STL, pick your printer, slice, check the layers and
save the G-code. No computer, no cloud, no internet permission.

## What it does

- **Any FDM printer**: about 950 printers from OrcaSlicer's profiles, plus generic Marlin,
  Klipper, RepRapFirmware and Repetier printers you can set up yourself (bed size, nozzle,
  start and end G-code).
- **OrcaSlicer's slicing**: walls, infill patterns, top and bottom shells, skirt and brim,
  retraction, temperatures, spiral vase, and **normal and tree supports**, all computed by
  OrcaSlicer 2.4.2's own engine.
- **3D plate**: orbit, pan and zoom, plus move, rotate and scale controls with a check that the
  model fits the build volume.
- **Layer preview**: step through the G-code layer by layer, coloured by feature, with print
  time and filament use.
- **Export**: save the G-code anywhere through the system file picker (SD card, USB drive,
  cloud folder) or share it with another app.

Bambu Lab printers are not listed yet: their G-code relies on parts of the engine this build
does not drive.

## Get the APK

The newest APK that passed the end-to-end slicing test is on the repository's
[**latest** release](https://github.com/whoxllm/OrcaMicro/releases/tag/latest):

1. Download `OrcaMicro.apk` from that release on your phone (or copy it over from a computer).
2. Open it and allow installs from that app when Android asks.

Every push also builds an APK in GitHub Actions: the **OrcaMicro-apk** artifact of a
successful **Build APK** run.

Requires Android 9 or newer on a 64-bit ARM phone, which covers practically every phone from
the last several years.

## Using it

1. **Pick your printer**: on first start choose the brand and model. If yours isn't listed,
   choose *Generic / custom printer* and set the bed size and start G-code under **Settings →
   Printer**.
2. **Open an STL**: use the **+** button, or "Open with" from a file manager.
3. **Place it**: the pencil button opens rotate, scale and position controls.
4. **Adjust settings**: layer height, infill, supports, temperatures and more, on top of the
   selected presets. *Reset* goes back to the preset.
5. **Slice**, then check the preview and tap **Save G-code**.

## How it is built

| Part | What it is |
|---|---|
| `app/` | The Android app: Kotlin, Jetpack Compose, OpenGL ES 2 views |
| `core/` | Pure Kotlin: STL files, model placement, OrcaSlicer profiles, settings, G-code parsing. Unit-tested on any JVM |
| `native/` | Builds OrcaSlicer's `libslic3r` for Android with a small JNI bridge (`liborcamicro_engine.so`) |

The slicing engine runs in its own Android process, so a crash in native code can't take the
app down. The design is in [docs/design.md](docs/design.md) and the build plan in [docs/plan.md](docs/plan.md).

### Building

The native engine needs the Android NDK (r27c) and takes a few hours from scratch:

```sh
export ANDROID_NDK_ROOT=/path/to/ndk/27.2.12479018
native/scripts/fetch-orca.sh         # clone OrcaSlicer v2.4.2 and apply the Android patch
native/scripts/build-deps.sh         # cross-compile Boost, TBB, CGAL, OpenCASCADE, OpenCV, ...
native/scripts/build-engine.sh app/src/main/jniLibs
```

Then the app, with OrcaSlicer's profiles:

```sh
ORCAMICRO_ORCA_DIR=/tmp/orca native/scripts/fetch-orca.sh --profiles-only
./gradlew :app:assembleRelease -PorcaResourcesDir=/tmp/orca/resources
```

Core tests run anywhere, without the Android SDK:

```sh
./gradlew :core:test -PcoreOnly -PorcaResourcesDir=/tmp/orca/resources
```

On a Linux desktop the same engine builds as `orcamicro_cli`, which runs one slice request (the
JSON the app sends, described in `native/bridge/engine.hpp`) and is the quickest way to test
engine changes. Configure OrcaSlicer's patched tree with `-DORCAMICRO_ENGINE=ON
-DORCAMICRO_DIR=$PWD/native -DSLIC3R_GUI=OFF` against desktop builds of the same libraries and
build the `orcamicro_cli` target.

In CI, `.github/workflows/engine.yml` builds the engine (cached per dependency) whenever
`native/` changes. `.github/workflows/build.yml` builds the APK and runs an end-to-end slicing
test on an Android emulator.

### Signing

Release APKs are signed with a key that is kept only in the repository's Actions secrets, never
in the code. CI publishes an APK only when it was signed with that key, so an APK on the
releases page always comes from this repository. The release notes list the certificate's
SHA-256 digest.

| Secret | Value |
|---|---|
| `ORCAMICRO_KEYSTORE_BASE64` | the PKCS12 keystore, base64-encoded |
| `ORCAMICRO_KEYSTORE_PASSWORD` | its password |

The key's alias is `orcamicro`. Locally, point `ORCAMICRO_KEYSTORE`, `ORCAMICRO_KEYSTORE_PASSWORD`
and optionally `ORCAMICRO_KEY_ALIAS` at a keystore; without them the release APK gets the debug
key, which is fine for testing.

Keep a copy of the keystore somewhere safe: Android only installs an update signed with the
same key, so losing it means users have to uninstall before installing a new build.

## License

OrcaMicro is free software under the [GNU Affero General Public License v3](LICENSE), the same
license as OrcaSlicer, which it is built on. If you share the APK, share the source with it.
This repository is that source.

OrcaSlicer is by SoftFever and contributors, building on Bambu Studio, PrusaSlicer and Slic3r.
The printer, process and filament profiles come from OrcaSlicer and its contributors.
