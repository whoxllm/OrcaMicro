// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once

#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

#include <nlohmann/json.hpp>

namespace Slic3r {
class DynamicPrintConfig;
} // namespace Slic3r

namespace orcamicro {

// Engine mutex shared between slice() and plugin_command() so inspect returns "busy" during a slice.
std::mutex &engine_mutex();

// Exception thrown on plugin failure during slicing; maps to stage = "plugin".
class PluginSliceError : public std::runtime_error {
public:
    PluginSliceError(const std::string &msg, nlohmann::json msgs = nlohmann::json::array())
        : std::runtime_error(msg), messages(std::move(msgs)) {}
    nlohmann::json messages;
};

// Manages CPython slicing-pipeline plugins for a single slice.
// When `request["plugins"]` is null, absent, or has empty `items`, Python is never touched.
class PluginsSession {
public:
    PluginsSession(const nlohmann::json &request, const std::string &work_dir);
    ~PluginsSession();

    PluginsSession(const PluginsSession &) = delete;
    PluginsSession &operator=(const PluginsSession &) = delete;

    // Overwrites `slicing_pipeline_plugin` and `plugins` in config from the request
    // (clearing desktop preset references when no plugins were sent).
    void prepare_config(Slic3r::DynamicPrintConfig &config) const;

    // Installs Print::set_slicing_pipeline_hook_fn when plugins are active.
    void install_hook();

    // Runs Step.psGCodePostProcess on the exported G-code file in place before responding.
    void run_post_process(const Slic3r::DynamicPrintConfig &config, const std::string &gcode_path);

    // Accumulated plugin step and blocked messages for EngineResponse.plugin_messages.
    nlohmann::json messages_json() const;

    bool active() const { return m_active; }

private:
    struct Impl;
    std::unique_ptr<Impl> m_impl;
    bool m_active = false;
};

// Executes a plugin command JSON (op: "inspect").
std::string plugin_command(const std::string &request_json);

} // namespace orcamicro
