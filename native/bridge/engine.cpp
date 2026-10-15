// SPDX-License-Identifier: AGPL-3.0-or-later
//
// OrcaMicro slicing engine. Drives OrcaSlicer's libslic3r the way OrcaSlicer's own command
// line (src/OrcaSlicer.cpp, CLI::run) does for one STL, one plate and one filament, without
// the desktop GUI classes the command line depends on.

#include "engine.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <iostream>
#include <map>
#include <mutex>
#include <set>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include <boost/filesystem.hpp>
#include <boost/log/expressions.hpp>
#include <boost/log/trivial.hpp>
#include <boost/log/utility/setup/console.hpp>
#include <boost/regex.hpp>
#include <nlohmann/json.hpp>

#include "libslic3r/libslic3r.h"
#include "libslic3r/Config.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Thread.hpp"
#include "libslic3r/Utils.hpp"
#include "libslic3r_version.h"

namespace orcamicro {
namespace {

using json = nlohmann::json;
namespace fs = boost::filesystem;
using namespace Slic3r;

// libslic3r keeps process-wide state (directories, statistics tables), so slices run one at a time.
std::mutex g_slice_mutex;

std::mutex g_progress_mutex;
int g_progress_percent = 0;
std::string g_progress_text;

void set_progress(int percent, const std::string &text)
{
    std::lock_guard<std::mutex> lock(g_progress_mutex);
    g_progress_percent = std::clamp(percent, 0, 100);
    g_progress_text = text;
}

// An error tagged with the pipeline stage it came from, so the app can explain it.
class EngineError : public std::runtime_error
{
public:
    EngineError(std::string stage, const std::string &message)
        : std::runtime_error(message), stage(std::move(stage)) {}
    std::string stage;
};

struct LoadedPreset
{
    DynamicPrintConfig config;
    std::string name;
    std::string filament_id;
};

LoadedPreset load_preset(const std::string &path, const std::string &expected_type)
{
    if (!fs::exists(path))
        throw EngineError("config", "The " + expected_type + " preset file is missing: " + path);

    LoadedPreset preset;
    std::map<std::string, std::string> key_values;
    std::string reason;
    preset.config.load_from_json(path, ForwardCompatibilitySubstitutionRule::Enable, key_values, reason);
    if (!reason.empty())
        throw EngineError("config", "Could not read the " + expected_type + " preset: " + reason);

    const std::string type = key_values["type"];
    if (type != expected_type)
        throw EngineError("config", "Expected a " + expected_type + " preset but " + path + " is a '" + type + "' preset");

    preset.name = key_values["name"];
    preset.filament_id = key_values["filament_id"];
    preset.config.normalize_fdm();
    return preset;
}

// Same as the CLI's update_full_config() for a freshly loaded printer or process preset:
// every option overrides the full config, except preset bookkeeping keys.
void apply_preset(DynamicPrintConfig &full, const DynamicPrintConfig &preset)
{
    static const std::set<std::string> skipped = {
        "compatible_prints", "compatible_printers", "model_id", "inherits", "dev_model_name",
        "name", "from", "type", "version", "setting_id", "instantiation"};
    for (const std::string &key : preset.keys()) {
        if (skipped.count(key) > 0)
            continue;
        const ConfigOption *source = preset.option(key);
        ConfigOption *target = full.option(key, true);
        if (source == nullptr || target == nullptr)
            throw EngineError("config", "Unknown setting in preset: " + key);
        target->set(source);
    }
}

// Same as the CLI's filament merge for filament slot 1: vector options are copied,
// scalar options in a filament preset are ignored.
void apply_filament(DynamicPrintConfig &full, const DynamicPrintConfig &filament)
{
    static const std::set<std::string> skipped = {
        "compatible_prints", "compatible_printers", "model_id", "dev_model_name", "filament_settings_id"};
    for (const std::string &key : filament.keys()) {
        if (skipped.count(key) > 0)
            continue;
        const ConfigOption *source = filament.option(key);
        if (source == nullptr)
            throw EngineError("config", "Unknown setting in filament preset: " + key);
        if (source->is_scalar())
            continue;
        ConfigOption *target = full.option(key, true);
        if (target == nullptr)
            throw EngineError("config", "Unknown setting in filament preset: " + key);
        target->set(source);
    }
}

void add_unique(std::vector<std::string> &list, const std::string &text)
{
    if (!text.empty() && std::find(list.begin(), list.end(), text) == list.end())
        list.push_back(text);
}

// Relative extrusion on Marlin needs "G92 E0" at every layer, and OrcaSlicer refuses to slice
// without it. A few vendor profiles leave it out or comment it out; add the reset Orca asks for.
void add_layer_e_reset(DynamicPrintConfig &config, std::vector<std::string> &warnings)
{
    static const boost::regex g92e0("^[ \\t]*[gG]92[ \\t]*[eE](0(\\.0*)?|\\.0+)[ \\t]*(;.*)?$");
    if (!config.opt_bool("use_relative_e_distances"))
        return;
    const GCodeFlavor flavor = config.option<ConfigOptionEnum<GCodeFlavor>>("gcode_flavor", true)->value;
    if (flavor != gcfMarlinLegacy && flavor != gcfMarlinFirmware)
        return;
    std::string &before = config.option<ConfigOptionString>("before_layer_change_gcode", true)->value;
    const std::string &after = config.option<ConfigOptionString>("layer_change_gcode", true)->value;
    if (boost::regex_search(before, g92e0) || boost::regex_search(after, g92e0))
        return;
    if (!before.empty() && before.back() != '\n')
        before += '\n';
    before += "G92 E0\n";
    warnings.push_back("Added \"G92 E0\" to the layer change G-code: relative extrusion needs it and this printer profile leaves it out.");
}

DynamicPrintConfig build_config(const json &request, std::vector<std::string> &warnings)
{
    const LoadedPreset machine = load_preset(request.at("machine_path").get<std::string>(), "machine");
    const LoadedPreset process = load_preset(request.at("process_path").get<std::string>(), "process");
    const LoadedPreset filament = load_preset(request.at("filament_path").get<std::string>(), "filament");

    DynamicPrintConfig config = DynamicPrintConfig::full_print_config();

    apply_preset(config, machine.config);
    config.set("printer_settings_id", machine.name, true);
    apply_preset(config, process.config);
    config.set("print_settings_id", process.name, true);
    apply_filament(config, filament.config);
    config.option<ConfigOptionStrings>("filament_settings_id", true)->values = {filament.name};
    config.option<ConfigOptionStrings>("filament_ids", true)->values = {filament.filament_id};

    // One filament: every extruder variant of it belongs to filament 1, mapped to extruder 1.
    const size_t variants = std::max<size_t>(1, config.option<ConfigOptionStrings>("filament_extruder_variant", true)->values.size());
    config.option<ConfigOptionInts>("filament_self_index", true)->values.assign(variants, 1);
    config.option<ConfigOptionInts>("filament_map", true)->values = {1};

    config.normalize_fdm();
    config.option<ConfigOptionEnum<PrinterTechnology>>("printer_technology", true)->value = ptFFF;

    // Fill every option the presets left out with OrcaSlicer's defaults.
    FullPrintConfig defaults;
    defaults.apply(config, true);
    config.apply(defaults, true);

    add_layer_e_reset(config, warnings);

    // OrcaSlicer's GUI only range-checks settings loaded from 3MF files, so system presets with
    // values outside the limits (some vendors ship them) still slice there. Do the same.
    for (const auto &[key, problem] : config.validate(true))
        add_unique(warnings, "Preset setting used as is: " + key + ": " + problem);
    return config;
}

Model load_model(const std::string &path, double center_x, double center_y)
{
    Model model;
    try {
        model = Model::read_from_file(path, nullptr, nullptr, LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances);
    } catch (const std::exception &e) {
        throw EngineError("model", std::string("Could not read the model: ") + e.what());
    }
    if (model.objects.empty())
        throw EngineError("model", "The model file contains no objects");

    model.add_default_instances();
    model.center_instances_around_point(Vec2d(center_x, center_y));
    for (ModelObject *object : model.objects)
        object->ensure_on_bed();
    return model;
}

void prepare_directories(const json &request)
{
    const std::string resources_dir = request.at("resources_dir").get<std::string>();
    const std::string work_dir = request.at("work_dir").get<std::string>();
    fs::create_directories(work_dir);
    fs::create_directories(fs::path(work_dir) / "data");
    fs::create_directories(fs::path(work_dir) / "tmp");

    // Boost.Log writes to stderr, which Android discards; keep it with the job instead.
    std::freopen((fs::path(work_dir) / "engine.log").string().c_str(), "w", stderr);
    // G-code export writes temporary files; Android has no usable /tmp.
    setenv("TMPDIR", (fs::path(work_dir) / "tmp").string().c_str(), 1);

    set_resources_dir(resources_dir);
    set_var_dir((fs::path(resources_dir) / "images").string());
    set_local_dir((fs::path(resources_dir) / "i18n").string());
    set_sys_shapes_dir((fs::path(resources_dir) / "shapes").string());
    set_custom_gcodes_dir((fs::path(resources_dir) / "custom_gcodes").string());
    set_data_dir((fs::path(work_dir) / "data").string());
    set_temporary_dir((fs::path(work_dir) / "tmp").string());
}

json run_slice(const json &request)
{
    prepare_directories(request);
    set_logging_level(request.value("log_level", 2));

    static std::once_flag once;
    std::call_once(once, [] {
        name_tbb_thread_pool_threads_set_locale();
        // Without a sink Boost.Log prints to stdout, which Android drops. stderr is the job's log.
        namespace logging = boost::log;
        logging::add_console_log(std::cerr, logging::keywords::auto_flush = true,
            logging::keywords::format = (logging::expressions::stream
                << "[" << logging::trivial::severity << "] " << logging::expressions::smessage));
    });
    save_main_thread_id();

    std::vector<std::string> warnings;
    set_progress(1, "Loading presets");
    DynamicPrintConfig config = build_config(request, warnings);

    set_progress(2, "Loading model");
    Model model = load_model(request.at("model_path").get<std::string>(),
                             request.at("center_x").get<double>(),
                             request.at("center_y").get<double>());

    Print print;
    print.is_BBL_printer() = false;
    // One plate at the bed origin. The GUI's plate list sets this; Print leaves it uninitialised.
    print.set_plate_origin(Vec3d::Zero());
    print.apply(model, config);
    if (print.empty())
        throw EngineError("validate", "Nothing to print. The model may be empty or entirely outside the bed.");

    StringObjectException warning;
    const StringObjectException error = print.validate(&warning);
    if (!error.string.empty())
        throw EngineError("validate", error.string);
    add_unique(warnings, warning.string);

    print.set_status_callback([&warnings](const PrintBase::SlicingStatus &status) {
        if (status.percent >= 0)
            set_progress(status.percent, status.text);
        if (status.warning_step != -1)
            add_unique(warnings, status.text);
    });

    // The CLI sets these static tables before every slice; brims and speeds depend on them.
    Model::setExtruderParams(config, 1);
    Model::setPrintSpeedTable(config, print.config());

    set_progress(4, "Slicing");
    try {
        print.process();
    } catch (const EngineError &) {
        throw;
    } catch (const std::exception &e) {
        throw EngineError("slice", e.what());
    }
    add_unique(warnings, print.get_conflict_string());

    GCodeProcessorResult result;
    std::string gcode_path;
    try {
        gcode_path = print.export_gcode(request.at("output_path").get<std::string>(), &result, nullptr);
    } catch (const std::exception &e) {
        throw EngineError("export", e.what());
    }
    if (result.gcode_check_result.error_code != 0)
        throw EngineError("export", "Some toolpaths fall outside the printable area or height of this printer.");

    set_progress(100, "Done");
    return json{{"ok", true}, {"gcode_path", gcode_path}, {"warnings", warnings}};
}

} // namespace

std::string slice(const std::string &request_json)
{
    std::lock_guard<std::mutex> lock(g_slice_mutex);
    set_progress(0, "Starting");
    json response;
    try {
        response = run_slice(json::parse(request_json));
    } catch (const EngineError &e) {
        response = json{{"ok", false}, {"stage", e.stage}, {"error", e.what()}};
    } catch (const json::exception &e) {
        response = json{{"ok", false}, {"stage", "request"}, {"error", std::string("Bad slice request: ") + e.what()}};
    } catch (const std::exception &e) {
        response = json{{"ok", false}, {"stage", "slice"}, {"error", e.what()}};
    } catch (...) {
        response = json{{"ok", false}, {"stage", "slice"}, {"error", "Unknown engine error"}};
    }
    std::fflush(stderr);
    return response.dump();
}

std::string progress()
{
    std::lock_guard<std::mutex> lock(g_progress_mutex);
    return json{{"percent", g_progress_percent}, {"text", g_progress_text}}.dump();
}

std::string version()
{
    return std::string("OrcaSlicer ") + SoftFever_VERSION;
}

} // namespace orcamicro
