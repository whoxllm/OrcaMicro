#pragma once

#include <sstream>
#include <string>
#include <utility>

#include <boost/format.hpp>

namespace Slic3r::GUI {

template <typename... Args>
inline std::string format(const std::string &fmt, Args &&...args)
{
    boost::format f(fmt);
    (void)std::initializer_list<int>{((f % std::forward<Args>(args)), 0)...};
    return f.str();
}

} // namespace Slic3r::GUI
