// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once

#include <string>

namespace orcamicro {

// Executes one CAD or STEP command against the process-local CadDocument session.
// Request and response are JSON objects (see cad.cpp for supported "op" values).
std::string cad_command(const std::string &request_json);

} // namespace orcamicro
