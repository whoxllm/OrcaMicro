// SPDX-License-Identifier: AGPL-3.0-or-later
// libslic3r parses SVGs with nanosvg (SVG import, embossed shapes), but OrcaSlicer compiles the
// nanosvg implementation in its GUI, which the engine leaves out.
#define NANOSVG_IMPLEMENTATION
#include "nanosvg/nanosvg.h"
