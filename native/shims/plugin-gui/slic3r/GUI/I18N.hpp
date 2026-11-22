#pragma once

#include <string>

namespace Slic3r::I18N {
inline std::string translate(const char *s) { return s ? std::string(s) : std::string(); }
inline std::string translate(const std::string &s) { return s; }
} // namespace Slic3r::I18N

#ifndef _L
#define _L(s) Slic3r::I18N::translate(s)
#endif
#ifndef _u8L
#define _u8L(s) Slic3r::I18N::translate(s)
#endif
