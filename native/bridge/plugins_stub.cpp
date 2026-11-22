// SPDX-License-Identifier: AGPL-3.0-or-later
// Desktop CLI stub when building orcamicro_cli without Android embedded Python.

#include "plugins.hpp"

#include "libslic3r/PrintConfig.hpp"

namespace orcamicro {

struct PluginsSession::Impl {};

PluginsSession::PluginsSession(const nlohmann::json &, const std::string &) = default;
PluginsSession::~PluginsSession() = default;

void PluginsSession::prepare_config(Slic3r::DynamicPrintConfig &config) const
{
    if (auto *sp = config.option<Slic3r::ConfigOptionStrings>("slicing_pipeline_plugin", false))
        sp->values.clear();
    if (auto *pl = config.option<Slic3r::ConfigOptionStrings>("plugins", false))
        pl->values.clear();
}

void PluginsSession::install_hook() {}

void PluginsSession::run_post_process(const Slic3r::DynamicPrintConfig &, const std::string &) {}

nlohmann::json PluginsSession::messages_json() const
{
    return nlohmann::json::array();
}

std::string plugin_command(const std::string &)
{
    return nlohmann::json{{"ok", false}, {"error", "Plugins are not supported in orcamicro_cli"}}.dump();
}

} // namespace orcamicro
