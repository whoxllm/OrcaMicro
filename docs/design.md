# OrcaMicro: a standalone OrcaSlicer APK

Date: 2026-10-15
Status: implemented

## Goal

An Android app that slices 3D models into G-code entirely on the phone, using OrcaSlicer's
own slicing engine. No PC, no cloud, no network permission. It installs as a sideloaded APK.

### Requirements

- An FDM 3D-print slicer that ships as a standalone APK.
- Any printer, through OrcaSlicer's profiles plus a custom profile (bed size, nozzle,
  start/end G-code).
- First version must include support generation and a layer-by-layer G-code preview, besides
  the core: STL import, move/rotate/scale on a 3D plate view, walls/infill/top/bottom,
  skirt/brim, retraction, temperatures, saved settings and G-code export.
- Built on OrcaSlicer, and licensed AGPL-3.0 like it.

### Assumptions

- Android 9 (API 28) or newer, 64-bit ARM phones only.
- One STL object per plate, one extruder and one filament. 3MF/OBJ, multiple objects and
  sending to printers over Wi-Fi come later.
- Bambu Lab printers are left out of the printer list: their G-code depends on BBL-specific
  engine paths this build does not drive.

### Success criteria

- The APK installs and slices a 20 mm cube and an overhang that needs supports, with OrcaSlicer's
  bundled profiles, producing G-code a printer accepts.
- CI proves this on every engine change by running the real engine on an emulator.

## Architecture

```
app (Kotlin, Compose)            core (pure Kotlin/JVM)          native (C++, NDK)
 ├─ Plate / Presets / Settings    ├─ STL io, placement            ├─ OrcaSlicer v2.4.2 libslic3r
 │  / Preview / About screens     ├─ profile catalog + resolver   │   (patched for a headless
 ├─ GLES2 plate + toolpath views  ├─ settings + preset edits      │    Android build)
 ├─ MainViewModel                 ├─ engine JSON protocol          ├─ bridge: engine.cpp + jni.cpp
 └─ EngineService (:engine proc)  └─ G-code parser                 └─ deps built from Orca recipes
        └─ liborcamicro_engine.so ◄───────────────── built by CI ───┘
```

### Why a JNI bridge and not Orca's CLI

OrcaSlicer's command line (src/OrcaSlicer.cpp) depends on desktop GUI classes (PartPlate,
Plater, GLCanvas3D, wxWidgets, GLFW) in both v2.4.2 and main. Building it means building the
GUI. The bridge instead calls libslic3r directly, following the CLI's steps for an STL loaded
with standalone presets:

1. Load the machine, process and filament JSON presets (`load_from_json`, `normalize_fdm`).
2. Merge them into `DynamicPrintConfig::full_print_config()` as the CLI's `update_full_config`
   does (preset bookkeeping keys skipped; filament vector options copied, scalars ignored).
3. Set `printer_settings_id`, `print_settings_id`, `filament_settings_id`, `filament_ids`,
   `filament_self_index`, `filament_map`; normalize; fill defaults from `FullPrintConfig`;
   `validate(true)`.
4. Read the STL, add default instances, centre the footprint on the requested bed point,
   `ensure_on_bed`.
5. `Print::apply`, `validate`, `setExtruderParams`/`setPrintSpeedTable`, `process`,
   `export_gcode` with a `GCodeProcessorResult`, check `gcode_check_result`.

The engine runs in a separate Android process (`:engine`) inside a foreground service. A native
crash only kills that process; the app reports it with the engine log. Cancel kills the process.

### Native build

- Pinned to OrcaSlicer v2.4.2 (commit 8500fcdc). `main` additionally requires an embedded
  Python.
- `native/patches/orcaslicer-v2.4.2-android-engine.patch` adds `ORCAMICRO_ENGINE`: no GUI,
  no CLI executable, no OpenGL/GLFW/OpenVDB/curl, and it adds the bridge to Orca's build.
- SLA hollowing (the only OpenVDB user) compiles to no-ops. OpenVDB needs OpenEXR, whose build
  runs generated tools and cannot be cross-compiled.
- OpenSSL is replaced by an MD5-only shim; libslic3r uses nothing else from it.
- Dependencies are cross-compiled one by one by `native/scripts/build-deps.sh` with the versions,
  checksums and patches of Orca's `deps/` recipes: expat, libpng, libjpeg-turbo, FreeType,
  Boost 1.84, oneTBB 2021.5, GMP, MPFR, Eigen 5.0.1, CGAL 5.6.3, cereal, NLopt, libnoise, Draco,
  OpenCASCADE 7.6, OpenCV 4.6 (core only). zlib comes from the NDK.
- NDK r27c, API 28, `c++_static`, 16 KB page alignment; everything links into one stripped
  `liborcamicro_engine.so`.

### Profiles

- Orca's `resources/profiles` JSON files ship as APK assets (about 53 MB raw, roughly 9 MB
  compressed in the APK).
- At build time `CatalogTool` (core) writes `orca/catalog.json`: printers per vendor with their
  compatible process and filament presets, defaults, and every preset's file path.
- Compatibility: a preset suits the printers its `compatible_printers` names; presets naming none
  suit all of the vendor's printers. `compatible_printers_condition` is not evaluated. Printers
  left with no process borrow from same-nozzle printers of the vendor, then the same model.
- At runtime `PresetResolver` flattens `inherits` chains (same vendor first, then Orca's
  filament library, then any vendor) into standalone presets marked `from: User`.

### Settings

`PrintSettings` lists the editable options (bed size, height, nozzle, G-code flavour, bed
surface, retraction, start/end G-code; layer heights, walls, shells, infill, supports, brim,
skirt, speeds, seam, vase mode; nozzle and bed temperatures, filament diameter, flow, fans).
Edits are stored per preset type and reset when that preset changes. The bed surface picks the
bed temperature set and is passed to the engine as `curr_bed_type`, because Orca defaults that
to Cool Plate, which gives PLA a 35 °C bed.

### App

- Plate: GLES2 bed grid, build volume and flat-shaded model, with orbit/pan/zoom gestures.
  Placement is rotation X→Y→Z about the model centre, uniform scale, and footprint centre on the
  bed. The model turns red if it leaves the build volume, and slicing is refused.
- Presets: searchable lists over 953 printers from 63 vendors.
- Preview: all extrusions in one VBO in layer order; layers below the current one are drawn
  dimmed, the current one at full brightness. Single-layer and travel toggles, and a legend
  that filters features.
- Export through the system file picker, or share the G-code with another app.
- State (selection, edits, placement, model) persists in app storage.

### Error handling

- The engine returns `{ok:false, stage, error}`. Stages are request, config, model, validate,
  slice and export, and the app turns each into a sentence. The engine log tail is shown.
- A missing engine library, a crashed engine process, out-of-memory on huge STL or G-code files,
  and invalid STL files all produce messages instead of crashes.

### Testing

- core: JVM unit tests (STL, placement, G-code parser, preset editor, resolver) and a sweep that
  resolves every printer and default preset in Orca's real profiles.
- MD5 shim checked against `md5sum` for boundary sizes and chunkings.
- Instrumented smoke test on an x86_64 API 34 emulator (it translates arm64): engine loads,
  slices a cube on a generic Marlin printer (layer count, walls, infill, centring, stats), and an
  overhang with normal and with tree supports.

### Delivery

- `.github/workflows/engine.yml` builds the engine when native/ changes, caches each dependency
  and ccache, and dispatches the APK workflow.
- `.github/workflows/build.yml` runs the core tests, compiles the app on every push, and once the
  engine is cached publishes the `OrcaMicro-apk` artifact and runs the smoke test.
- APKs are signed with a checked-in sideload key so updates install over each other. Replace it
  before distributing.

## Licensing

OrcaSlicer is AGPL-3.0, so OrcaMicro is AGPL-3.0 too. Anyone given the APK must be able to get
the complete source. The About screen links to it and credits OrcaSlicer and the bundled
libraries.
