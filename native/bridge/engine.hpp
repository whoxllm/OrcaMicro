// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once

#include <string>

namespace orcamicro {

// Slices one model on one plate with one filament, following the same steps OrcaSlicer's
// command line takes for an STL loaded with --load-settings and --load-filaments.
//
// request_json fields:
//   resources_dir   directory holding Orca's runtime resources (info/, flush/)
//   work_dir        writable scratch directory for this job
//   model_path      STL file, already rotated and scaled by the app
//   machine_path    standalone (fully flattened) printer preset JSON
//   process_path    standalone process preset JSON
//   filament_path   standalone filament preset JSON
//   output_path     where to write the G-code
//   center_x/y      bed position (mm) for the centre of the model's footprint
//   log_level       optional Boost.Log level, 0 (fatal) .. 5 (trace); default 2
//
// Returns {"ok":true,"gcode_path":...,"warnings":[...]} or
//         {"ok":false,"stage":"request|config|model|validate|slice|export","error":...}.
std::string slice(const std::string &request_json);

// Latest progress of the running slice as {"percent":N,"text":"..."}. Safe to call from any thread.
std::string progress();

// Version of the bundled OrcaSlicer engine, e.g. "OrcaSlicer 2.4.2".
std::string version();

} // namespace orcamicro
