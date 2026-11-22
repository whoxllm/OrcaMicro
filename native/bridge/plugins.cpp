// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Embedded CPython plugin host, audit guard rail, and slicing-pipeline dispatcher for Android.

#include "plugins.hpp"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <exception>
#include <fcntl.h>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include <boost/algorithm/string.hpp>
#include <boost/filesystem.hpp>
#include <boost/log/trivial.hpp>

#include <pybind11/embed.h>
#include <pybind11/pybind11.h>
#include <pybind11/stl.h>

#include "libslic3r/Exception.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Utils.hpp"
#include "libslic3r_version.h"

#include "slic3r/plugin/PluginAuditManager.hpp"
#include "slic3r/plugin/PluginConfig.hpp"
#include "slic3r/plugin/PluginFsUtils.hpp"
#include "slic3r/plugin/PluginManager.hpp"
#include "slic3r/plugin/PythonInterpreter.hpp"
#include "slic3r/plugin/PythonPluginBridge.hpp"
#include "slic3r/plugin/PythonPluginInterface.hpp"
#include "slic3r/plugin/host/PluginHostBindings.hpp"
#include "slic3r/plugin/host/PluginHostUi.hpp"
#include "slic3r/plugin/pluginTypes/slicingPipeline/SlicingPipelinePluginCapability.hpp"

namespace py = pybind11;
namespace fs = boost::filesystem;
using json = nlohmann::json;

namespace orcamicro {
namespace {

struct RecordedMessage {
    std::string plugin_id;
    std::string capability;
    std::string step;
    std::string status;
    std::string permission;
    bool has_permission = false;
    std::string message;
};

struct ActiveItemPolicy {
    std::string plugin_id;
    std::string capability;
    std::string dir;
    std::string work_dir;
    std::string config_json;
    std::string current_step;
    std::unordered_set<std::string> allowed;
    std::vector<RecordedMessage> *messages = nullptr;
    std::mutex *messages_mutex = nullptr;
};

std::mutex g_policy_mutex;
std::unordered_map<std::string, ActiveItemPolicy> g_active_policies;
std::vector<std::string> *g_inspect_requested_permissions = nullptr;
std::mutex g_python_init_mutex;

void record_blocked_message(const ActiveItemPolicy &policy, const std::string &permission, const std::string &detail)
{
    if (!policy.messages || !policy.messages_mutex)
        return;
    std::lock_guard<std::mutex> lock(*policy.messages_mutex);
    for (const auto &existing : *policy.messages) {
        if (existing.status == "blocked" &&
            existing.plugin_id == policy.plugin_id &&
            existing.capability == policy.capability &&
            existing.step == policy.current_step &&
            existing.permission == permission) {
            return;
        }
    }
    policy.messages->push_back(RecordedMessage{
        policy.plugin_id,
        policy.capability,
        policy.current_step,
        "blocked",
        permission,
        true,
        detail,
    });
}

void push_unique(std::vector<std::string> &vec, const std::string &val)
{
    if (!val.empty() && std::find(vec.begin(), vec.end(), val) == vec.end())
        vec.push_back(val);
}

const char *step_to_string(Slic3r::SlicingPipelineStepPlugin step)
{
    switch (step) {
    case Slic3r::SlicingPipelineStepPlugin::posSlice: return "posSlice";
    case Slic3r::SlicingPipelineStepPlugin::posPerimeters: return "posPerimeters";
    case Slic3r::SlicingPipelineStepPlugin::posEstimateCurledExtrusions: return "posEstimateCurledExtrusions";
    case Slic3r::SlicingPipelineStepPlugin::posPrepareInfill: return "posPrepareInfill";
    case Slic3r::SlicingPipelineStepPlugin::posInfill: return "posInfill";
    case Slic3r::SlicingPipelineStepPlugin::posIroning: return "posIroning";
    case Slic3r::SlicingPipelineStepPlugin::posContouring: return "posContouring";
    case Slic3r::SlicingPipelineStepPlugin::posSupportMaterial: return "posSupportMaterial";
    case Slic3r::SlicingPipelineStepPlugin::posDetectOverhangsForLift: return "posDetectOverhangsForLift";
    case Slic3r::SlicingPipelineStepPlugin::posSimplifyPath: return "posSimplifyPath";
    case Slic3r::SlicingPipelineStepPlugin::psWipeTower: return "psWipeTower";
    case Slic3r::SlicingPipelineStepPlugin::psSkirtBrim: return "psSkirtBrim";
    case Slic3r::SlicingPipelineStepPlugin::psGCodePostProcess: return "psGCodePostProcess";
    }
    return "Unknown";
}

// Mirrors OrcaSlicer's desktop audit_event_categories table in PluginAuditManager.cpp.
const std::unordered_map<std::string, Slic3r::AuditEventCategory> &audit_event_categories()
{
    using Cat = Slic3r::AuditEventCategory;
    static const std::unordered_map<std::string, Cat> table = {
        {"glob.glob", Cat::FsRead},
        {"glob.glob/2", Cat::FsRead},
        {"os.fwalk", Cat::FsRead},
        {"os.getxattr", Cat::FsRead},
        {"os.listdir", Cat::FsRead},
        {"os.listdrives", Cat::FsRead},
        {"os.listmounts", Cat::FsRead},
        {"os.listvolumes", Cat::FsRead},
        {"os.listxattr", Cat::FsRead},
        {"os.scandir", Cat::FsRead},
        {"os.walk", Cat::FsRead},
        {"pathlib.Path.glob", Cat::FsRead},
        {"pathlib.Path.rglob", Cat::FsRead},

        {"os.chflags", Cat::FsReadWrite},
        {"os.chmod", Cat::FsReadWrite},
        {"os.chown", Cat::FsReadWrite},
        {"os.removexattr", Cat::FsReadWrite},
        {"os.rename", Cat::FsReadWrite},
        {"os.setxattr", Cat::FsReadWrite},
        {"os.truncate", Cat::FsReadWrite},
        {"os.utime", Cat::FsReadWrite},
        {"shutil.chown", Cat::FsReadWrite},
        {"shutil.copymode", Cat::FsReadWrite},
        {"shutil.copystat", Cat::FsReadWrite},
        {"shutil.copyfile", Cat::FsReadWrite},
        {"shutil.copytree", Cat::FsReadWrite},
        {"shutil.make_archive", Cat::FsReadWrite},
        {"shutil.move", Cat::FsReadWrite},
        {"shutil.unpack_archive", Cat::FsReadWrite},

        {"os.link", Cat::FsCreate},
        {"os.mkdir", Cat::FsCreate},
        {"os.symlink", Cat::FsCreate},
        {"tempfile.mkdtemp", Cat::FsCreate},
        {"tempfile.mkstemp", Cat::FsCreate},
        {"_winapi.CreateJunction", Cat::FsCreate},

        {"os.remove", Cat::FsDelete},
        {"os.rmdir", Cat::FsDelete},
        {"shutil.rmtree", Cat::FsDelete},

        {"http.client.connect", Cat::Http},
        {"http.client.send", Cat::Http},
        {"urllib.Request", Cat::Http},

        {"socket.__new__", Cat::Socket},
        {"socket.bind", Cat::Socket},
        {"socket.connect", Cat::Socket},
        {"socket.getaddrinfo", Cat::Socket},
        {"socket.gethostbyaddr", Cat::Socket},
        {"socket.gethostbyname", Cat::Socket},
        {"socket.gethostname", Cat::Socket},
        {"socket.getnameinfo", Cat::Socket},
        {"socket.getservbyname", Cat::Socket},
        {"socket.getservbyport", Cat::Socket},
        {"socket.sendmsg", Cat::Socket},
        {"socket.sendto", Cat::Socket},

        {"os.fork", Cat::ProcessCreate},
        {"os.forkpty", Cat::ProcessCreate},
        {"os.posix_spawn", Cat::ProcessCreate},
        {"os.spawn", Cat::ProcessCreate},
        {"os.system", Cat::ProcessCreate},
        {"os.startfile", Cat::ProcessCreate},
        {"os.startfile/2", Cat::ProcessCreate},
        {"pty.spawn", Cat::ProcessCreate},
        {"subprocess.Popen", Cat::ProcessCreate},
        {"_winapi.CreateProcess", Cat::ProcessCreate},
        {"_posixsubprocess.fork_exec", Cat::ProcessCreate},

        {"_thread.start_new_thread", Cat::Threading},
        {"os.exec", Cat::ProcessReplace},
    };
    return table;
}

const std::unordered_set<std::string> &operation_root_events()
{
    static const std::unordered_set<std::string> roots = {
        "urllib.Request",
        "http.client.connect",
        "os.walk",
        "os.fwalk",
        "glob.glob",
        "glob.glob/2",
        "pathlib.Path.glob",
        "pathlib.Path.rglob",
        "subprocess.Popen",
        "pty.spawn",
        "tempfile.mkstemp",
        "tempfile.mkdtemp",
        "shutil.copyfile",
        "shutil.copymode",
        "shutil.copystat",
        "shutil.copytree",
        "shutil.chown",
        "shutil.move",
        "shutil.rmtree",
        "shutil.make_archive",
        "shutil.unpack_archive",
    };
    return roots;
}

const std::unordered_set<std::string> &two_path_fs_events()
{
    static const std::unordered_set<std::string> events = {
        "os.rename",
        "os.link",
        "os.symlink",
        "shutil.copyfile",
        "shutil.copytree",
        "shutil.copymode",
        "shutil.copystat",
        "shutil.move",
        "shutil.unpack_archive",
        "_winapi.CreateJunction",
    };
    return events;
}

bool is_fs_category(Slic3r::AuditEventCategory c)
{
    using Cat = Slic3r::AuditEventCategory;
    return c == Cat::FsRead || c == Cat::FsReadWrite || c == Cat::FsCreate || c == Cat::FsDelete;
}

PyObject *tuple_item(PyObject *args, Py_ssize_t index)
{
    if (!args || !PyTuple_Check(args) || index < 0 || index >= PyTuple_GET_SIZE(args))
        return nullptr;
    return PyTuple_GET_ITEM(args, index);
}

std::string py_unicode(PyObject *obj)
{
    if (!obj || !PyUnicode_Check(obj))
        return {};
    const char *s = PyUnicode_AsUTF8(obj);
    if (!s) {
        PyErr_Clear();
        return {};
    }
    return s;
}

bool py_fspath(PyObject *obj, std::string &out)
{
    if (!obj)
        return false;
    if (PyLong_Check(obj))
        return false;
    PyObject *path_obj = PyOS_FSPath(obj);
    if (!path_obj) {
        PyErr_Clear();
        return false;
    }
    bool ok = false;
    if (PyUnicode_Check(path_obj)) {
        out = py_unicode(path_obj);
        ok = true;
    } else if (PyBytes_Check(path_obj)) {
        const char *s = PyBytes_AsString(path_obj);
        if (s) {
            out = s;
            ok = true;
        } else {
            PyErr_Clear();
        }
    }
    Py_DECREF(path_obj);
    return ok && !out.empty();
}

Slic3r::AuditEventCategory classify_open(PyObject *args, bool &is_fd)
{
    PyObject *arg0 = tuple_item(args, 0);
    std::string dummy_path;
    if (!arg0 || PyLong_Check(arg0) || !py_fspath(arg0, dummy_path)) {
        is_fd = true;
        return Slic3r::AuditEventCategory::None;
    }
    is_fd = false;

    PyObject *mode_obj = tuple_item(args, 1);
    const std::string mode = py_unicode(mode_obj);
    if (!mode.empty()) {
        if (mode.find_first_of("wax+") == std::string::npos)
            return Slic3r::AuditEventCategory::FsRead;
        return Slic3r::AuditEventCategory::FsReadWrite;
    }

    PyObject *flags_obj = tuple_item(args, 2);
    if (flags_obj && PyLong_Check(flags_obj)) {
        const long flags = PyLong_AsLong(flags_obj);
        if (!PyErr_Occurred()) {
            const bool ro = (flags & O_ACCMODE) == O_RDONLY;
            const bool modifies = (flags & (O_CREAT | O_TRUNC | O_APPEND)) != 0;
            if (ro && !modifies)
                return Slic3r::AuditEventCategory::FsRead;
        } else {
            PyErr_Clear();
        }
    }
    return Slic3r::AuditEventCategory::FsReadWrite;
}

std::vector<fs::path> s_stdlib_dirs;
std::vector<fs::path> s_site_package_dirs;
std::unordered_map<std::string, bool> s_stdlib_file_verdicts;

void collect_stdlib_dirs()
{
    s_stdlib_dirs.clear();
    s_site_package_dirs.clear();
    s_stdlib_file_verdicts.clear();

    PyObject *sysconfig = PyImport_ImportModule("sysconfig");
    PyObject *paths = sysconfig ? PyObject_CallMethod(sysconfig, "get_paths", nullptr) : nullptr;
    if (paths && PyDict_Check(paths)) {
        const auto collect = [paths](const char *key, std::vector<fs::path> &out) {
            const std::string dir = py_unicode(PyDict_GetItemString(paths, key));
            if (dir.empty())
                return;
            boost::system::error_code ec;
            const fs::path resolved = fs::weakly_canonical(dir, ec);
            out.push_back(ec ? fs::path(dir) : resolved);
        };
        collect("stdlib", s_stdlib_dirs);
        collect("platstdlib", s_stdlib_dirs);
        collect("purelib", s_site_package_dirs);
        collect("platlib", s_site_package_dirs);
    }
    Py_XDECREF(paths);
    Py_XDECREF(sysconfig);
    if (PyErr_Occurred())
        PyErr_Clear();
}

std::vector<PyObject *> stack_frames()
{
    std::vector<PyObject *> frames;
    PyFrameObject *frame = PyEval_GetFrame();
    Py_XINCREF(frame);
    while (frame) {
        frames.push_back(reinterpret_cast<PyObject *>(frame));
        PyFrameObject *back = PyFrame_GetBack(frame);
        Py_DECREF(frame);
        frame = back;
    }
    return frames;
}

bool is_stdlib_frame(PyObject *frame)
{
    PyCodeObject *code = PyFrame_GetCode(reinterpret_cast<PyFrameObject *>(frame));
    if (!code)
        return false;
    const std::string filename = py_unicode(code->co_filename);
    Py_DECREF(code);
    return Slic3r::PluginAuditManager::is_stdlib_file(filename);
}

void take_returned_frames(std::vector<PyObject *> &held, std::vector<PyObject *> &dropped)
{
    if (held.empty())
        return;
    const std::vector<PyObject *> stack = stack_frames();
    const auto running = [&stack](PyObject *frame) {
        if (std::find(stack.begin(), stack.end(), frame) != stack.end())
            return true;
        PyObject *gen = PyFrame_GetGenerator(reinterpret_cast<PyFrameObject *>(frame));
        const bool suspended = gen != nullptr;
        Py_XDECREF(gen);
        return suspended;
    };
    const auto returned = std::stable_partition(held.begin(), held.end(), running);
    dropped.insert(dropped.end(), returned, held.end());
    held.erase(returned, held.end());
}

std::string ascii_lower(std::string value)
{
    for (char &c : value)
        if (c >= 'A' && c <= 'Z')
            c = static_cast<char>(c - 'A' + 'a');
    return value;
}

std::string host_from_authority(std::string authority)
{
    const size_t at = authority.rfind('@');
    if (at != std::string::npos)
        authority.erase(0, at + 1);
    if (authority.empty())
        return {};
    if (authority.front() == '[') {
        const size_t close = authority.find(']');
        if (close == std::string::npos)
            return {};
        authority.erase(close + 1);
        return ascii_lower(authority);
    }
    const size_t colon = authority.find(':');
    if (colon != std::string::npos)
        authority.erase(colon);
    if (authority.find_first_of(" \t\r\n/?#@") != std::string::npos)
        return {};
    return ascii_lower(authority);
}

bool is_dangling_symlink(const fs::path &p)
{
    boost::system::error_code ec;
    const fs::file_status link_st = fs::symlink_status(p, ec);
    if (ec || !fs::is_symlink(link_st))
        return false;
    ec.clear();
    const fs::file_status target_st = fs::status(p, ec);
    return ec || !fs::exists(target_st);
}

bool ensure_python_initialized(const std::string &python_home, std::string &error)
{
    std::lock_guard<std::mutex> init_lock(g_python_init_mutex);
    Slic3r::PythonInterpreter &interp = Slic3r::PythonInterpreter::instance();
    if (!interp.is_initialized()) {
        if (python_home.empty()) {
            error = "python_home is empty";
            return false;
        }
        setenv("ORCAMICRO_PYTHON_HOME", python_home.c_str(), 1);

        // PythonInterpreter::initialize() writes pycache_prefix and stderr log under data_dir().
        // Keep that in a persistent runtime folder under python_home so slice cache cleanup cannot delete it.
        const std::string prev_data_dir = Slic3r::data_dir();
        const fs::path runtime_dir = fs::path(python_home) / "runtime";
        boost::system::error_code ec;
        fs::create_directories(runtime_dir, ec);
        Slic3r::set_data_dir(runtime_dir.string());

        const bool ok = interp.initialize();

        if (!prev_data_dir.empty())
            Slic3r::set_data_dir(prev_data_dir);

        if (!ok) {
            error = interp.last_error();
            return false;
        }

        Slic3r::PythonGILState gil;
        if (gil) {
            try {
                py::module_::import("sys").attr("dont_write_bytecode") = true;
            } catch (const std::exception &) {
            }
        }
    }

    // Always allow read-only access to the extracted python_home stdlib.
    Slic3r::PluginAuditManager::instance().add_global_allowed_root(fs::path(python_home), false);
    return true;
}

std::string format_py_exception(py::error_already_set &err)
{
    try {
        py::module_ tb = py::module_::import("traceback");
        py::list lines = tb.attr("format_exception_only")(err.type(), err.value());
        std::string out;
        for (auto item : lines)
            out += item.cast<std::string>();
        boost::algorithm::trim(out);
        if (!out.empty())
            return out;
    } catch (...) {
    }
    std::string fallback = err.what();
    boost::algorithm::trim(fallback);
    return fallback;
}

struct Pep723Meta {
    std::string name;
    std::string description;
    std::string author;
    std::string version;
    std::string type;
    std::string requires_python;
};

bool read_pep723_block(const std::string &file_path, std::string &toml_out, std::string &error)
{
    std::ifstream f(file_path);
    if (!f) {
        error = "Could not open plugin file: " + file_path;
        return false;
    }
    toml_out.clear();
    bool in_block = false;
    std::string line;
    while (std::getline(f, line)) {
        if (!line.empty() && line.back() == '\r')
            line.pop_back();
        if (!in_block) {
            if (line == "# /// script")
                in_block = true;
            continue;
        }
        if (line == "# ///") {
            in_block = false;
            break;
        }
        if (line.size() >= 2 && line[0] == '#' && (line[1] == ' ' || line[1] == '\t'))
            toml_out += line.substr(2) + "\n";
        else if (line == "#")
            toml_out += "\n";
    }
    return true;
}

bool parse_pep723_with_python(const std::string &toml_text, Pep723Meta &meta, std::string &error)
{
    if (toml_text.empty())
        return true;
    try {
        py::module_ tomllib = py::module_::import("tomllib");
        py::dict data = tomllib.attr("loads")(toml_text);
        if (data.contains("requires-python") && py::isinstance<py::str>(data["requires-python"]))
            meta.requires_python = data["requires-python"].cast<std::string>();
        if (data.contains("tool") && py::isinstance<py::dict>(data["tool"])) {
            py::dict tool = data["tool"].cast<py::dict>();
            if (tool.contains("orcaslicer") && py::isinstance<py::dict>(tool["orcaslicer"])) {
                py::dict orca = tool["orcaslicer"].cast<py::dict>();
                if (orca.contains("plugin") && py::isinstance<py::dict>(orca["plugin"])) {
                    py::dict plugin = orca["plugin"].cast<py::dict>();
                    auto read_str = [&](const char *k, std::string &dst) {
                        if (plugin.contains(k) && py::isinstance<py::str>(plugin[k]))
                            dst = plugin[k].cast<std::string>();
                    };
                    read_str("name", meta.name);
                    read_str("description", meta.description);
                    read_str("author", meta.author);
                    read_str("version", meta.version);
                    read_str("type", meta.type);
                }
            }
        }
        return true;
    } catch (py::error_already_set &err) {
        error = "Invalid PEP 723 metadata: " + format_py_exception(err);
        return false;
    } catch (const std::exception &ex) {
        error = std::string("Invalid PEP 723 metadata: ") + ex.what();
        return false;
    }
}

bool check_requires_python_312(const std::string &spec, std::string &error)
{
    if (spec.empty())
        return true;
    try {
        py::dict ns;
        ns["spec"] = spec;
        py::exec(R"PY(
import re
ok = True
for raw_clause in spec.split(","):
    clause = raw_clause.strip()
    if not clause:
        continue
    m = re.match(r'^(~=|===|==|!=|<=|>=|<|>)\s*([0-9]+(?:\.[0-9]+)*(?:\.\*)?)$', clause)
    if not m:
        continue
    op, ver = m.group(1), m.group(2)
    cur = (3, 12, 12)
    if ver.endswith(".*"):
        parts = tuple(int(x) for x in ver[:-2].split("."))
        if op == "==" and cur[:len(parts)] != parts:
            ok = False
        elif op == "!=" and cur[:len(parts)] == parts:
            ok = False
    else:
        parts = tuple(int(x) for x in ver.split("."))
        c = cur[:len(parts)] + (0,) * max(0, len(parts) - len(cur))
        p = parts + (0,) * max(0, len(cur) - len(parts))
        if op == ">=" and not (cur >= p): ok = False
        elif op == ">" and not (cur > p): ok = False
        elif op == "<=" and not (cur <= p): ok = False
        elif op == "<" and not (cur < p): ok = False
        elif op == "==" and not (cur[:len(parts)] == parts): ok = False
        elif op == "!=" and (cur[:len(parts)] == parts): ok = False
        elif op == "~=" and (len(parts) >= 2 and not (cur >= p and cur[:len(parts)-1] == parts[:-1])): ok = False
)PY", ns);
        const bool ok = ns["ok"].cast<bool>();
        if (!ok) {
            error = "Plugin requires Python " + spec + " (bundled runtime is 3.12)";
            return false;
        }
        return true;
    } catch (...) {
        return true;
    }
}

struct LoadedModuleHandle {
    std::string module_name;
    std::string dir;
    bool added_sys_path = false;
    std::vector<std::string> added_modules;
};

void unload_loaded_module(const LoadedModuleHandle &handle)
{
    if (handle.module_name.empty() && handle.dir.empty())
        return;
    if (!Slic3r::PythonInterpreter::instance().is_initialized())
        return;
    Slic3r::PythonGILState gil;
    if (!gil)
        return;
    try {
        py::module_ sys = py::module_::import("sys");
        py::dict modules = sys.attr("modules");
        for (const std::string &mod_name : handle.added_modules) {
            if (modules.contains(mod_name.c_str()))
                modules.attr("pop")(mod_name, py::none());
        }
        if (!handle.module_name.empty() && modules.contains(handle.module_name.c_str()))
            modules.attr("pop")(handle.module_name, py::none());
        if (!handle.dir.empty()) {
            boost::system::error_code ec;
            const fs::path canon_dir = fs::weakly_canonical(handle.dir, ec);
            if (!canon_dir.empty()) {
                std::vector<std::string> dir_mods;
                for (auto item : modules) {
                    if (!py::isinstance<py::str>(item.first))
                        continue;
                    if (py::hasattr(item.second, "__file__")) {
                        py::object f = item.second.attr("__file__");
                        if (!f.is_none() && py::isinstance<py::str>(f)) {
                            if (Slic3r::is_inside_allowed_root(fs::path(f.cast<std::string>()), canon_dir))
                                dir_mods.push_back(item.first.cast<std::string>());
                        }
                    }
                }
                for (const std::string &mod_name : dir_mods) {
                    if (modules.contains(mod_name.c_str()))
                        modules.attr("pop")(mod_name, py::none());
                }
            }
        }
        if (handle.added_sys_path && !handle.dir.empty()) {
            py::list sys_path = sys.attr("path");
            for (Py_ssize_t i = PyList_Size(sys_path.ptr()) - 1; i >= 0; --i) {
                PyObject *entry = PyList_GetItem(sys_path.ptr(), i);
                if (entry && py_unicode(entry) == handle.dir) {
                    PySequence_DelItem(sys_path.ptr(), i);
                }
            }
        }
    } catch (...) {
        if (PyErr_Occurred())
            PyErr_Clear();
    }
}

bool load_plugin_file(const std::string &file_path,
                      const std::string &dir,
                      const std::string &capture_key,
                      std::vector<Slic3r::CapturedCapability> &out_caps,
                      LoadedModuleHandle &out_handle,
                      std::string &error)
{
    static std::atomic<uint64_t> counter{0};
    const uint64_t id = ++counter;
    out_handle = LoadedModuleHandle{};
    out_handle.module_name = "_orcamicro_plugin_" + std::to_string(id);
    out_handle.dir = dir;

    if (!fs::exists(file_path) || !fs::is_regular_file(file_path)) {
        error = "Plugin file not found: " + file_path;
        return false;
    }

    Slic3r::PythonGILState gil;
    if (!gil) {
        error = "Python interpreter is shutting down";
        return false;
    }

    py::module_ sys = py::module_::import("sys");
    py::list sys_path = sys.attr("path");
    py::dict modules = sys.attr("modules");

    std::unordered_set<std::string> before_modules;
    for (auto item : modules) {
        if (py::isinstance<py::str>(item.first))
            before_modules.insert(item.first.cast<std::string>());
    }

    if (!dir.empty()) {
        bool present = false;
        for (auto entry : sys_path) {
            if (py::isinstance<py::str>(entry) && entry.cast<std::string>() == dir) {
                present = true;
                break;
            }
        }
        if (!present) {
            sys_path.attr("insert")(0, dir);
            out_handle.added_sys_path = true;
        }
    }

    Slic3r::PythonPluginBridge::instance().begin_plugin_capture(capture_key);

    auto collect_added_modules = [&]() {
        boost::system::error_code ec;
        const fs::path canon_dir = dir.empty() ? fs::path() : fs::weakly_canonical(dir, ec);
        for (auto item : modules) {
            if (!py::isinstance<py::str>(item.first))
                continue;
            std::string mod_name = item.first.cast<std::string>();
            if (before_modules.count(mod_name))
                continue;
            bool belongs = (mod_name == out_handle.module_name ||
                            mod_name.rfind(out_handle.module_name + ".", 0) == 0);
            if (!belongs && !canon_dir.empty() && py::hasattr(item.second, "__file__")) {
                py::object f = item.second.attr("__file__");
                if (!f.is_none() && py::isinstance<py::str>(f)) {
                    if (Slic3r::is_inside_allowed_root(fs::path(f.cast<std::string>()), canon_dir))
                        belongs = true;
                }
            }
            if (belongs)
                out_handle.added_modules.push_back(std::move(mod_name));
        }
    };

    try {
        py::module_ importlib_util = py::module_::import("importlib.util");
        py::object spec = importlib_util.attr("spec_from_file_location")(out_handle.module_name, file_path);
        if (spec.is_none() || !py::hasattr(spec, "loader") || spec.attr("loader").is_none()) {
            Slic3r::PythonPluginBridge::instance().cancel_plugin_capture(capture_key);
            collect_added_modules();
            unload_loaded_module(out_handle);
            out_handle = LoadedModuleHandle{};
            error = "Could not load Python module from " + file_path;
            return false;
        }
        py::object module = importlib_util.attr("module_from_spec")(spec);
        modules[py::str(out_handle.module_name)] = module;
        spec.attr("loader").attr("exec_module")(module);
        collect_added_modules();
    } catch (py::error_already_set &err) {
        error = format_py_exception(err);
        Slic3r::PythonPluginBridge::instance().cancel_plugin_capture(capture_key);
        collect_added_modules();
        unload_loaded_module(out_handle);
        out_handle = LoadedModuleHandle{};
        return false;
    } catch (const std::exception &ex) {
        error = ex.what();
        Slic3r::PythonPluginBridge::instance().cancel_plugin_capture(capture_key);
        collect_added_modules();
        unload_loaded_module(out_handle);
        out_handle = LoadedModuleHandle{};
        return false;
    }

    out_caps = Slic3r::PythonPluginBridge::instance().finalize_plugin_capture(capture_key, capture_key, error);
    if (out_caps.empty()) {
        if (error.empty())
            error = "Plugin did not register any capabilities";
        unload_loaded_module(out_handle);
        out_handle = LoadedModuleHandle{};
        return false;
    }

    for (auto &cap : out_caps) {
        const Slic3r::PluginCapabilityType cap_type = cap.instance->get_type();
        cap.instance->set_resolved_identity(cap.name, cap_type);
    }
    return true;
}

std::string sanitize_pep723_name(std::string name)
{
    std::replace(name.begin(), name.end(), ';', '_');
    return name;
}

} // namespace

fs::path normalized_dir_path(const std::string &dir)
{
    if (dir.empty())
        return {};
    fs::path p = fs::path(dir).lexically_normal();
    while (!p.empty() && p.filename() == ".")
        p = p.parent_path();
    return p;
}

} // namespace orcamicro

// ============================================================================
// Implementations of Slic3r plugin symbols required by upstream plugin/*.cpp
// ============================================================================

namespace Slic3r {

thread_local std::string PluginAuditManager::m_current_plugin_key;
thread_local std::string PluginAuditManager::m_current_capability_name;
thread_local std::vector<AllowedRoot> PluginAuditManager::m_scoped_allowed_roots;
thread_local std::vector<PyObject *> PluginAuditManager::m_root_frames;
thread_local int PluginAuditManager::m_releasing_frames = 0;
thread_local bool PluginAuditManager::m_audit_denial_pending = false;
thread_local bool PluginAuditManager::m_has_last_violation = false;
thread_local AuditViolation PluginAuditManager::m_last_violation;

bool is_inside_allowed_root(const boost::filesystem::path &candidate, const boost::filesystem::path &allowed_root)
{
    if (candidate.empty() || allowed_root.empty())
        return false;
    if (orcamicro::is_dangling_symlink(candidate))
        return false;

    boost::system::error_code ec;
    fs::path c = fs::weakly_canonical(candidate, ec);
    if (ec)
        c = fs::absolute(candidate).lexically_normal();
    fs::path r = fs::weakly_canonical(allowed_root, ec);
    if (ec)
        r = fs::absolute(allowed_root).lexically_normal();

    auto ci = c.begin();
    auto ri = r.begin();
    while (ri != r.end() && ci != c.end() && *ri == *ci) {
        ++ri;
        ++ci;
    }
    if (ri != r.end())
        return false;
    for (; ci != c.end(); ++ci) {
        if (*ci == "..")
            return false;
    }
    return true;
}

ScopedPluginAuditContext::ScopedPluginAuditContext(const std::string &plugin_key, const std::string &capability_name)
    : m_plugin_key(plugin_key)
    , m_capability(capability_name)
    , m_previous_id(PluginAuditManager::instance().current_plugin())
    , m_previous_capability(PluginAuditManager::instance().current_capability())
    , m_previous_scoped_roots(PluginAuditManager::m_scoped_allowed_roots)
    , m_previous_root_frames(std::move(PluginAuditManager::m_root_frames))
{
    PluginAuditManager::instance().set_current_plugin(plugin_key);
    PluginAuditManager::instance().set_current_capability(capability_name);
    PluginAuditManager::m_scoped_allowed_roots.clear();
    PluginAuditManager::m_root_frames.clear();
}

ScopedPluginAuditContext::~ScopedPluginAuditContext()
{
    PluginAuditManager::instance().release_frames(PluginAuditManager::m_root_frames);
    PluginAuditManager::m_root_frames = std::move(m_previous_root_frames);
    PluginAuditManager::instance().set_current_plugin(m_previous_id);
    PluginAuditManager::instance().set_current_capability(m_previous_capability);
    PluginAuditManager::m_scoped_allowed_roots = std::move(m_previous_scoped_roots);
}

PluginAuditManager &PluginAuditManager::instance()
{
    static PluginAuditManager mgr;
    return mgr;
}

void PluginAuditManager::install_hook()
{
    orcamicro::collect_stdlib_dirs();
    if (PySys_AddAuditHook(audit_hook, this) < 0)
        BOOST_LOG_TRIVIAL(error) << "[AUDIT] Failed to install CPython audit hook";
    else
        BOOST_LOG_TRIVIAL(info) << "[AUDIT] CPython audit hook installed";
}

void PluginAuditManager::set_current_plugin(const std::string &plugin_key) { m_current_plugin_key = plugin_key; }
std::string PluginAuditManager::current_plugin() const { return m_current_plugin_key; }
void PluginAuditManager::clear_current_plugin() { m_current_plugin_key.clear(); }
void PluginAuditManager::set_current_capability(const std::string &name) { m_current_capability_name = name; }
std::string PluginAuditManager::current_capability() const { return m_current_capability_name; }
void PluginAuditManager::clear_current_capability() { m_current_capability_name.clear(); }

void PluginAuditManager::add_global_allowed_root(const fs::path &root, bool allow_write)
{
    if (root.empty())
        return;
    std::lock_guard<std::mutex> lock(m_mutex);
    for (const auto &r : m_global_allowed_roots) {
        if (r.path == root && r.allow_write == allow_write)
            return;
    }
    m_global_allowed_roots.push_back({root, allow_write});
}

void PluginAuditManager::add_scoped_allowed_root(const fs::path &root, bool allow_write)
{
    if (!root.empty())
        m_scoped_allowed_roots.push_back({root, allow_write});
}

void PluginAuditManager::add_denied_filename(const std::string &filename)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_denied_filenames.push_back(filename);
}

std::vector<std::string> PluginAuditManager::default_denied_filenames() { return {}; }
bool PluginAuditManager::is_denied_filename(const fs::path &) const { return false; }
void PluginAuditManager::add_denied_path_keyword(const std::string &keyword)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_denied_path_keywords.push_back(keyword);
}
std::vector<std::string> PluginAuditManager::default_denied_path_keywords() { return {}; }
bool PluginAuditManager::is_denied_path_keyword(const fs::path &) const { return false; }
bool PluginAuditManager::is_denied_path(const fs::path &) const { return false; }

AuditDecision PluginAuditManager::check_path_access(const fs::path &candidate, bool is_write)
{
    for (const auto &root : m_scoped_allowed_roots) {
        if ((!is_write || root.allow_write) && is_inside_allowed_root(candidate, root.path))
            return {true, {}};
    }
    std::lock_guard<std::mutex> lock(m_mutex);
    for (const auto &root : m_global_allowed_roots) {
        if ((!is_write || root.allow_write) && is_inside_allowed_root(candidate, root.path))
            return {true, {}};
    }
    return {false, "outside allowed roots"};
}

AuditDecision PluginAuditManager::check_open(const std::string &path, const std::string &mode)
{
    const bool is_write = mode.empty() || mode.find_first_of("wax+") != std::string::npos;
    return check_path_access(fs::path(path), is_write);
}

bool PluginAuditManager::request_permissions(const std::string &, const PluginPermissions &requested)
{
    std::lock_guard<std::mutex> lock(orcamicro::g_policy_mutex);
    if (orcamicro::g_inspect_requested_permissions != nullptr) {
        auto &out = *orcamicro::g_inspect_requested_permissions;
        if (!requested.fs_read.empty())
            orcamicro::push_unique(out, "fs_read");
        if (!requested.fs_readwrite.empty())
            orcamicro::push_unique(out, "fs_readwrite");
        if (!requested.network_http.empty())
            orcamicro::push_unique(out, "network_http");
        if (!requested.network_socket.empty())
            orcamicro::push_unique(out, "network_socket");
        if (!requested.process.empty())
            orcamicro::push_unique(out, "process");
        if (requested.threading)
            orcamicro::push_unique(out, "threading");
    }
    // Never prompt or abort load on Android; slicing enforces effective allowed permissions.
    return true;
}

bool PluginAuditManager::request_network_permissions(const std::string &, const std::vector<std::string> &)
{
    return true;
}

std::string PluginAuditManager::normalize_network_url(const std::string &url)
{
    const size_t scheme_end = url.find("://");
    if (scheme_end == std::string::npos || scheme_end == 0)
        return {};
    const std::string scheme = orcamicro::ascii_lower(url.substr(0, scheme_end));
    if (scheme != "http" && scheme != "https")
        return {};
    const size_t auth_start = scheme_end + 3;
    const size_t auth_end = url.find_first_of("/?#", auth_start);
    const std::string authority = url.substr(auth_start, auth_end == std::string::npos ? std::string::npos : auth_end - auth_start);
    const std::string host = orcamicro::host_from_authority(authority);
    if (host.empty())
        return {};
    return scheme + "://" + host;
}

bool PluginAuditManager::network_permission_matches(const std::vector<std::string> &, const std::string &) { return false; }

void PluginAuditManager::report_violation(const AuditViolation &violation)
{
    m_last_violation = violation;
    m_has_last_violation = true;
    m_audit_denial_pending = true;
}

bool PluginAuditManager::audit_denial_pending() const { return m_audit_denial_pending; }
void PluginAuditManager::clear_audit_denial() { m_audit_denial_pending = false; }
void PluginAuditManager::clear_last_violation()
{
    m_has_last_violation = false;
    m_last_violation = AuditViolation{};
}

bool PluginAuditManager::last_violation(AuditViolation &violation) const
{
    if (m_has_last_violation)
        violation = m_last_violation;
    return m_has_last_violation;
}

void PluginAuditManager::record_root_frame()
{
    PyObject *frame = reinterpret_cast<PyObject *>(PyEval_GetFrame());
    if (!frame || !orcamicro::is_stdlib_frame(frame))
        return;
    if (std::find(m_root_frames.begin(), m_root_frames.end(), frame) != m_root_frames.end())
        return;
    Py_INCREF(frame);
    m_root_frames.push_back(frame);
}

bool PluginAuditManager::covered_by_root_frame() const
{
    if (m_root_frames.empty())
        return false;
    for (PyObject *frame : orcamicro::stack_frames()) {
        if (std::find(m_root_frames.begin(), m_root_frames.end(), frame) != m_root_frames.end())
            return true;
        if (!orcamicro::is_stdlib_frame(frame))
            return false;
    }
    return false;
}

bool PluginAuditManager::releasing_frames() const { return m_releasing_frames > 0; }

bool PluginAuditManager::is_stdlib_file(const std::string &filename)
{
    if (boost::algorithm::starts_with(filename, "<frozen "))
        return true;
    if (filename.empty() || filename.front() == '<')
        return false;

    const auto [it, inserted] = orcamicro::s_stdlib_file_verdicts.try_emplace(filename, false);
    if (inserted) {
        const fs::path file(filename);
        const auto inside_any = [&file](const std::vector<fs::path> &dirs) {
            return std::any_of(dirs.begin(), dirs.end(), [&file](const fs::path &dir) {
                return is_inside_allowed_root(file, dir);
            });
        };
        it->second = inside_any(orcamicro::s_stdlib_dirs) && !inside_any(orcamicro::s_site_package_dirs);
    }
    return it->second;
}

bool PluginAuditManager::is_stdlib_path(const fs::path &path)
{
    if (path.empty())
        return false;
    const auto inside_any = [&path](const std::vector<fs::path> &dirs) {
        return std::any_of(dirs.begin(), dirs.end(), [&path](const fs::path &dir) {
            return is_inside_allowed_root(path, dir);
        });
    };
    return inside_any(orcamicro::s_stdlib_dirs) && !inside_any(orcamicro::s_site_package_dirs);
}

void PluginAuditManager::release_frames(std::vector<PyObject *> &frames)
{
    if (frames.empty())
        return;
    PyObject *const error = PyErr_GetRaisedException();
    const bool denial_pending = m_audit_denial_pending;
    const bool has_violation = m_has_last_violation;
    const AuditViolation violation = m_last_violation;

    std::vector<PyObject *> released;
    released.swap(frames);
    ++m_releasing_frames;
    for (PyObject *frame : released)
        Py_DECREF(frame);
    --m_releasing_frames;

    m_audit_denial_pending = denial_pending;
    m_has_last_violation = has_violation;
    m_last_violation = violation;
    PyErr_SetRaisedException(error);
}

void PluginAuditManager::set_permission_prompt_for_testing(PermissionPrompt) {}
void PluginAuditManager::set_permission_message_prompt_for_testing(PermissionMessagePrompt) {}

int PluginAuditManager::audit_hook(const char *event, PyObject *args, void *user_data)
{
    auto *mgr = static_cast<PluginAuditManager *>(user_data);
    if (!mgr)
        return 0;

    const std::string item_key = mgr->current_plugin();
    if (item_key.empty())
        return 0;

    const std::string event_name(event ? event : "");
    if (event_name.empty())
        return 0;

    orcamicro::ActiveItemPolicy policy;
    bool has_policy = false;
    {
        std::lock_guard<std::mutex> lock(orcamicro::g_policy_mutex);
        auto it = orcamicro::g_active_policies.find(item_key);
        if (it != orcamicro::g_active_policies.end()) {
            policy = it->second;
            has_policy = true;
        }
    }
    if (!has_policy)
        return 0;

    auto deny = [&](const std::string &permission, const std::string &detail) -> int {
        orcamicro::record_blocked_message(policy, permission, detail);
        mgr->report_violation({item_key, event_name, detail});
        PyErr_SetString(PyExc_PermissionError, detail.c_str());
        return -1;
    };

    // Native-code loading via ctypes is always refused regardless of granted permissions.
    if (event_name == "ctypes.dlopen" || event_name.rfind("ctypes.", 0) == 0) {
        return deny("native", "Native code loading is disabled (" + event_name + ")");
    }

    AuditEventCategory category = AuditEventCategory::None;
    if (event_name == "open") {
        bool is_fd = false;
        category = orcamicro::classify_open(args, is_fd);
        if (is_fd)
            return 0;
    } else {
        const auto &cat_map = orcamicro::audit_event_categories();
        auto it = cat_map.find(event_name);
        if (it == cat_map.end())
            return 0;
        category = it->second;
    }

    std::vector<PyObject *> returned_frames;
    orcamicro::take_returned_frames(m_root_frames, returned_frames);

    auto finish = [&](int rc) -> int {
        mgr->release_frames(returned_frames);
        return rc;
    };

    if (event_name != "urllib.Request" && mgr->covered_by_root_frame())
        return finish(0);

    if (orcamicro::is_fs_category(category)) {
        const Py_ssize_t count = orcamicro::two_path_fs_events().count(event_name) ? 2 : 1;
        std::vector<fs::path> targets;
        for (Py_ssize_t i = 0; i < count; ++i) {
            PyObject *arg = orcamicro::tuple_item(args, i);
            std::string p;
            if (orcamicro::py_fspath(arg, p)) {
                targets.emplace_back(p);
            } else if (i == 0 && (arg == nullptr || arg == Py_None) &&
                       (event_name == "os.listdir" || event_name == "os.scandir" ||
                        event_name == "os.walk" || event_name == "os.fwalk")) {
                boost::system::error_code ec;
                targets.push_back(fs::current_path(ec));
            }
        }
        if (targets.empty())
            return finish(0);

        const bool is_write = category != AuditEventCategory::FsRead;
        const fs::path dir_path = orcamicro::normalized_dir_path(policy.dir);
        const fs::path data_dir = dir_path.empty() ? fs::path() : (dir_path / "data");
        const fs::path plugins_root = dir_path.empty() ? fs::path() : dir_path.parent_path();

        if (is_write && !plugins_root.empty()) {
            const bool forbidden_plugin_write = std::any_of(targets.begin(), targets.end(), [&](const fs::path &target) {
                if (!policy.work_dir.empty() && is_inside_allowed_root(target, fs::path(policy.work_dir)))
                    return false;
                return is_inside_allowed_root(target, plugins_root) &&
                       (data_dir.empty() || !is_inside_allowed_root(target, data_dir));
            });
            if (forbidden_plugin_write)
                return finish(deny("fs_readwrite", "Writing outside plugin data/ is blocked (" + event_name + ")"));
        }

        const bool all_always_allowed = std::all_of(targets.begin(), targets.end(), [&](const fs::path &target) {
            if (!is_write && !dir_path.empty() && is_inside_allowed_root(target, dir_path))
                return true;
            if (is_write && !data_dir.empty() && is_inside_allowed_root(target, data_dir))
                return true;
            if (!policy.work_dir.empty() && is_inside_allowed_root(target, fs::path(policy.work_dir)))
                return true;
            return mgr->check_path_access(target, is_write).allowed;
        });

        if (all_always_allowed) {
            if (orcamicro::operation_root_events().count(event_name))
                mgr->record_root_frame();
            return finish(0);
        }

        if (category == AuditEventCategory::FsRead) {
            if (policy.allowed.count("fs_read") || policy.allowed.count("fs_readwrite")) {
                if (orcamicro::operation_root_events().count(event_name))
                    mgr->record_root_frame();
                return finish(0);
            }
            return finish(deny("fs_read", "File read blocked (" + event_name + ")"));
        }

        if (policy.allowed.count("fs_readwrite")) {
            if (orcamicro::operation_root_events().count(event_name))
                mgr->record_root_frame();
            return finish(0);
        }
        return finish(deny("fs_readwrite", "File write blocked (" + event_name + ")"));
    }

    switch (category) {
    case AuditEventCategory::Http:
        if (policy.allowed.count("network_http")) {
            if (orcamicro::operation_root_events().count(event_name))
                mgr->record_root_frame();
            return finish(0);
        }
        return finish(deny("network_http", "Network HTTP request blocked (" + event_name + ")"));

    case AuditEventCategory::Socket:
        if (policy.allowed.count("network_socket")) {
            return finish(0);
        }
        return finish(deny("network_socket", "Network socket access blocked (" + event_name + ")"));

    case AuditEventCategory::ProcessCreate:
    case AuditEventCategory::ProcessReplace:
        if (policy.allowed.count("process")) {
            if (orcamicro::operation_root_events().count(event_name))
                mgr->record_root_frame();
            return finish(0);
        }
        return finish(deny("process", "Process launch blocked (" + event_name + ")"));

    case AuditEventCategory::Threading:
        if (policy.allowed.count("threading"))
            return finish(0);
        return finish(deny("threading", "Background thread blocked (" + event_name + ")"));

    default:
        return finish(0);
    }
}

void Plugin::release_module() {}

PluginManager &PluginManager::instance()
{
    PythonInterpreter::instance();
    static PluginManager inst;
    return inst;
}

PluginManager::~PluginManager() = default;

std::string PluginManager::get_storage_dir(const std::string &plugin_key) const
{
    std::lock_guard<std::mutex> lock(orcamicro::g_policy_mutex);
    auto it = orcamicro::g_active_policies.find(plugin_key);
    if (it == orcamicro::g_active_policies.end() || it->second.dir.empty())
        throw std::runtime_error("Plugin storage directory is unavailable");
    const fs::path data_dir = orcamicro::normalized_dir_path(it->second.dir) / "data";
    boost::system::error_code ec;
    fs::create_directories(data_dir, ec);
    return data_dir.string();
}

void PluginManager::dispatch_lifecycle_event(LifecycleEvent, const LifecycleEventContext &) {}

nlohmann::json capability_get_config(const PluginCapabilityInterface &capability)
{
    const std::string &key = capability.audit_plugin_key();
    std::string raw;
    {
        std::lock_guard<std::mutex> lock(orcamicro::g_policy_mutex);
        auto it = orcamicro::g_active_policies.find(key);
        if (it != orcamicro::g_active_policies.end())
            raw = it->second.config_json;
    }
    if (raw.empty())
        return nlohmann::json::object();
    nlohmann::json parsed = nlohmann::json::parse(raw, nullptr, false);
    if (parsed.is_discarded())
        return nlohmann::json::object();
    return parsed;
}

std::string capability_get_config_version(const PluginCapabilityInterface &) { return {}; }
bool capability_save_config(const PluginCapabilityInterface &, const nlohmann::json &) { return false; }

bool extract_zip_to_directory(const fs::path &, const fs::path &, std::string &error)
{
    error = "Wheel packages are not supported";
    return false;
}

namespace host_bindings {
void register_app(pybind11::module_ &host)
{
    host.def("preset_bundle", []() -> pybind11::object {
        throw std::runtime_error("Preset bundle is not available on Android");
    });
    host.def("app_language", []() -> std::string { return "en_US"; });
}

void register_presets(pybind11::module_ &host)
{
    host.def_submodule("presets", "Preset API (not available in headless engine)");
}
} // namespace host_bindings

void PluginHostUi::RegisterBindings(pybind11::module_ &host)
{
    auto ui = host.def_submodule("ui", "Plugin UI (not supported during headless slicing)");
    ui.def("create_window", [](py::args, py::kwargs) -> py::object {
        throw std::runtime_error("orca.host.ui.create_window is not supported on Android");
    });
    ui.def("message", [](py::args, py::kwargs) -> py::object {
        throw std::runtime_error("orca.host.ui.message is not supported on Android");
    });
}

void PluginHostUi::run_on_ui_thread(std::function<void()>) {}
void PluginHostUi::close_windows_for_plugin(const std::string &) {}

} // namespace Slic3r

// ============================================================================
// orcamicro::PluginsSession & plugin_command
// ============================================================================

namespace orcamicro {

namespace {
struct LoadedItem {
    std::string item_key;
    std::string plugin_id;
    std::string capability;
    std::string display_name;
    std::shared_ptr<Slic3r::SlicingPipelinePluginCapability> cap;
    LoadedModuleHandle module_handle;
};
} // namespace

struct PluginsSession::Impl {
    std::string work_dir;
    std::vector<LoadedItem> items;
    std::vector<RecordedMessage> messages;
    std::mutex messages_mutex;
    bool hook_installed = false;

    void cleanup()
    {
        if (hook_installed) {
            Slic3r::Print::set_slicing_pipeline_hook_fn(nullptr);
            hook_installed = false;
        }
        {
            std::lock_guard<std::mutex> lock(g_policy_mutex);
            for (const auto &entry : items)
                g_active_policies.erase(entry.item_key);
        }
        if (Slic3r::PythonInterpreter::instance().is_initialized()) {
            Slic3r::PythonGILState gil;
            if (gil) {
                for (auto &entry : items) {
                    entry.cap.reset();
                    unload_loaded_module(entry.module_handle);
                }
            }
        }
        items.clear();
    }

    json build_messages_json()
    {
        json arr = json::array();
        std::lock_guard<std::mutex> lock(messages_mutex);
        for (const auto &m : messages) {
            json obj{
                {"plugin_id", m.plugin_id},
                {"capability", m.capability},
                {"step", m.step},
                {"status", m.status},
                {"message", m.message},
            };
            if (m.has_permission)
                obj["permission"] = m.permission;
            arr.push_back(std::move(obj));
        }
        return arr;
    }

    void run_step(Slic3r::Print *print,
                  const Slic3r::PrintObject *object,
                  Slic3r::SlicingPipelineStepPlugin step,
                  const std::string &gcode_path,
                  const Slic3r::DynamicPrintConfig *full_config)
    {
        const std::string step_str = step_to_string(step);
        for (const auto &entry : items) {
            {
                std::lock_guard<std::mutex> lock(g_policy_mutex);
                auto it = g_active_policies.find(entry.item_key);
                if (it != g_active_policies.end())
                    it->second.current_step = step_str;
            }
            Slic3r::PluginAuditManager::instance().clear_audit_denial();
            Slic3r::PluginAuditManager::instance().clear_last_violation();

            Slic3r::ExecutionResult r;
            try {
                Slic3r::PythonGILState gil;
                if (!gil)
                    throw std::runtime_error("Python interpreter is shutting down");
                if (print != nullptr && print->canceled())
                    throw Slic3r::CanceledException();

                Slic3r::SlicingPipelineContext ctx;
                ctx.orca_version = SoftFever_VERSION;
                ctx.step = step;
                ctx.print = print;
                ctx.object = object;
                if (step == Slic3r::SlicingPipelineStepPlugin::psGCodePostProcess) {
                    ctx.gcode_path = gcode_path;
                    ctx.host = "File";
                    ctx.output_name = fs::path(gcode_path).filename().string();
                    ctx.full_config = full_config;
                }
                r = entry.cap->execute(ctx);
            } catch (const Slic3r::CanceledException &) {
                throw;
            } catch (const std::exception &ex) {
                throw PluginSliceError(entry.display_name + ": " + ex.what(), build_messages_json());
            }

            Slic3r::PluginAuditManager::instance().clear_audit_denial();
            Slic3r::PluginAuditManager::instance().clear_last_violation();

            if (r.status == Slic3r::PluginResult::FatalError)
                throw PluginSliceError(entry.display_name + ": " + r.message, build_messages_json());

            std::string status;
            if (r.status == Slic3r::PluginResult::RecoverableError)
                status = "error";
            else if (r.status == Slic3r::PluginResult::Skipped && !r.message.empty())
                status = "skipped";
            else if (!r.message.empty())
                status = "ok";

            if (!status.empty()) {
                std::lock_guard<std::mutex> lock(messages_mutex);
                messages.push_back(RecordedMessage{
                    entry.plugin_id,
                    entry.capability,
                    step_str,
                    std::move(status),
                    "",
                    false,
                    r.message,
                });
            }
        }
    }
};

PluginsSession::PluginsSession(const nlohmann::json &request, const std::string &work_dir)
{
    const auto it = request.find("plugins");
    if (it == request.end() || it->is_null() || !it->is_object())
        return;
    const auto items_it = it->find("items");
    if (items_it == it->end() || !items_it->is_array() || items_it->empty())
        return;

    const std::string python_home = it->value("python_home", std::string());
    std::string init_error;
    if (!ensure_python_initialized(python_home, init_error))
        throw PluginSliceError("Plugin runtime failed to start: " + init_error);

    m_impl = std::make_unique<Impl>();
    m_impl->work_dir = work_dir;
    m_active = true;

    try {
        for (size_t idx = 0; idx < items_it->size(); ++idx) {
            const json &jitem = (*items_it)[idx];
            const std::string plugin_id = jitem.value("plugin_id", std::string());
            const std::string capability = jitem.value("capability", std::string());
            const std::string path = jitem.value("path", std::string());
            const std::string dir = jitem.value("dir", std::string());
            const std::string config_json = jitem.value("config", std::string("{}"));

            std::unordered_set<std::string> allowed;
            if (jitem.contains("allowed") && jitem["allowed"].is_array()) {
                for (const auto &perm : jitem["allowed"]) {
                    if (perm.is_string())
                        allowed.insert(perm.get<std::string>());
                }
            }

            std::string display_name = !plugin_id.empty() ? plugin_id : fs::path(path).stem().string();
            {
                std::string toml_text;
                std::string err;
                if (read_pep723_block(path, toml_text, err) && !toml_text.empty()) {
                    Slic3r::PythonGILState gil;
                    if (gil) {
                        Pep723Meta meta;
                        std::string toml_err;
                        if (parse_pep723_with_python(toml_text, meta, toml_err) && !meta.name.empty())
                            display_name = sanitize_pep723_name(meta.name);
                    }
                }
            }

            const std::string item_key = "slice_item_" + std::to_string(idx);
            std::vector<Slic3r::CapturedCapability> captured;
            LoadedModuleHandle handle;
            std::string load_err;
            if (!load_plugin_file(path, dir, item_key, captured, handle, load_err))
                throw PluginSliceError(display_name + ": " + load_err);

            std::shared_ptr<Slic3r::SlicingPipelinePluginCapability> matched;
            {
                Slic3r::PythonGILState gil;
                if (!gil) {
                    unload_loaded_module(handle);
                    throw PluginSliceError(display_name + ": Python interpreter is shutting down");
                }
                for (auto &c : captured) {
                    auto sp = std::dynamic_pointer_cast<Slic3r::SlicingPipelinePluginCapability>(c.instance);
                    if (!sp)
                        continue;
                    if (c.name == capability) {
                        matched = sp;
                        break;
                    }
                    try {
                        py::object py_obj = py::cast(c.instance);
                        std::string cls_name = py_obj.attr("__class__").attr("__name__").cast<std::string>();
                        if (cls_name == capability) {
                            matched = sp;
                            break;
                        }
                    } catch (...) {
                    }
                }
                if (!matched && capability.empty() && captured.size() == 1)
                    matched = std::dynamic_pointer_cast<Slic3r::SlicingPipelinePluginCapability>(captured[0].instance);
                captured.clear(); // Release other capabilities while GIL is held.
            }

            if (!matched) {
                unload_loaded_module(handle);
                throw PluginSliceError(display_name + ": slicing-pipeline capability '" + capability + "' not found");
            }

            matched->set_audit_plugin_key(item_key);
            matched->set_enabled(true);

            if (!dir.empty()) {
                boost::system::error_code ec;
                fs::create_directories(normalized_dir_path(dir) / "data", ec);
            }

            {
                std::lock_guard<std::mutex> lock(g_policy_mutex);
                g_active_policies[item_key] = ActiveItemPolicy{
                    plugin_id,
                    capability,
                    dir,
                    work_dir,
                    config_json,
                    "",
                    std::move(allowed),
                    &m_impl->messages,
                    &m_impl->messages_mutex,
                };
            }

            m_impl->items.push_back(LoadedItem{
                item_key,
                plugin_id,
                capability,
                display_name,
                std::move(matched),
                std::move(handle),
            });
        }
    } catch (...) {
        m_impl->cleanup();
        m_impl.reset();
        m_active = false;
        throw;
    }
}

PluginsSession::~PluginsSession()
{
    if (m_impl)
        m_impl->cleanup();
}

void PluginsSession::prepare_config(Slic3r::DynamicPrintConfig &config) const
{
    std::vector<std::string> cap_names;
    if (m_active && m_impl) {
        cap_names.reserve(m_impl->items.size());
        for (const auto &entry : m_impl->items)
            cap_names.push_back(entry.cap->name());
    }
    config.option<Slic3r::ConfigOptionStrings>("slicing_pipeline_plugin", true)->values = std::move(cap_names);
    config.option<Slic3r::ConfigOptionStrings>("plugins", true)->values.clear();
}

void PluginsSession::install_hook()
{
    if (!m_active || !m_impl) {
        Slic3r::Print::set_slicing_pipeline_hook_fn(nullptr);
        return;
    }
    Impl *impl = m_impl.get();
    Slic3r::Print::set_slicing_pipeline_hook_fn(
        [impl](Slic3r::Print &print, const Slic3r::PrintObject *object, Slic3r::SlicingPipelineStepPlugin step) {
            impl->run_step(&print, object, step, "", nullptr);
        });
    m_impl->hook_installed = true;
}

void PluginsSession::run_post_process(const Slic3r::DynamicPrintConfig &config, const std::string &gcode_path)
{
    if (!m_active || !m_impl)
        return;
    m_impl->run_step(nullptr, nullptr, Slic3r::SlicingPipelineStepPlugin::psGCodePostProcess, gcode_path, &config);
}

nlohmann::json PluginsSession::messages_json() const
{
    if (!m_impl)
        return json::array();
    return m_impl->build_messages_json();
}

std::string plugin_command(const std::string &request_json)
{
    json request;
    try {
        request = json::parse(request_json);
    } catch (const json::exception &e) {
        return json{{"ok", false}, {"error", std::string("Bad plugin request: ") + e.what()}}.dump();
    }

    const std::string op = request.value("op", std::string("inspect"));
    if (op != "inspect")
        return json{{"ok", false}, {"error", "Unsupported plugin operation: " + op}}.dump();

    std::unique_lock<std::mutex> busy_lock(engine_mutex(), std::try_to_lock);
    if (!busy_lock.owns_lock())
        return json{{"ok", false}, {"error", "busy"}}.dump();

    const std::string python_home = request.value("python_home", std::string());
    const std::string path = request.value("path", std::string());
    const std::string dir = request.value("dir", std::string());

    if (path.empty() || !fs::exists(path) || !fs::is_regular_file(path))
        return json{{"ok", false}, {"error", "Plugin file not found: " + path}}.dump();

    std::string init_error;
    if (!ensure_python_initialized(python_home, init_error))
        return json{{"ok", false}, {"error", init_error}}.dump();

    std::string toml_text;
    std::string read_err;
    if (!read_pep723_block(path, toml_text, read_err))
        return json{{"ok", false}, {"error", read_err}}.dump();

    Pep723Meta meta;
    {
        Slic3r::PythonGILState gil;
        if (!gil)
            return json{{"ok", false}, {"error", "Python interpreter is shutting down"}}.dump();
        std::string toml_err;
        if (!parse_pep723_with_python(toml_text, meta, toml_err))
            return json{{"ok", false}, {"error", toml_err}}.dump();
        std::string py_ver_err;
        if (!check_requires_python_312(meta.requires_python, py_ver_err))
            return json{{"ok", false}, {"error", py_ver_err}}.dump();
    }

    std::vector<std::string> requested_permissions;
    struct InspectScopeGuard {
        explicit InspectScopeGuard(std::vector<std::string> *vec)
        {
            std::lock_guard<std::mutex> lock(g_policy_mutex);
            g_inspect_requested_permissions = vec;
        }
        ~InspectScopeGuard()
        {
            std::lock_guard<std::mutex> lock(g_policy_mutex);
            g_inspect_requested_permissions = nullptr;
        }
    } inspect_guard(&requested_permissions);

    std::vector<Slic3r::CapturedCapability> captured;
    LoadedModuleHandle handle;
    std::string load_err;
    if (!load_plugin_file(path, dir, "inspect", captured, handle, load_err))
        return json{{"ok", false}, {"error", load_err}}.dump();

    json caps_json = json::array();
    std::string first_cap_type;
    std::string first_cap_name;
    try {
        Slic3r::PythonGILState gil;
        if (!gil) {
            unload_loaded_module(handle);
            return json{{"ok", false}, {"error", "Python interpreter is shutting down"}}.dump();
        }
        for (auto &c : captured) {
            const std::string type_str = Slic3r::plugin_capability_type_to_string(c.instance->type());
            if (first_cap_type.empty()) {
                first_cap_type = type_str;
                first_cap_name = c.name;
            }
            const std::string default_cfg = c.instance->get_default_config().dump();
            json config_ui = nullptr;
            if (c.instance->has_config_ui()) {
                std::string html = c.instance->get_config_ui();
                if (!html.empty())
                    config_ui = std::move(html);
            }
            caps_json.push_back(json{
                {"name", c.name},
                {"type", type_str},
                {"default_config", default_cfg},
                {"config_ui", config_ui},
            });
        }
        captured.clear();
    } catch (const std::exception &ex) {
        Slic3r::PythonGILState gil;
        captured.clear();
        unload_loaded_module(handle);
        return json{{"ok", false}, {"error", ex.what()}}.dump();
    }

    unload_loaded_module(handle);

    const std::string out_name = !meta.name.empty() ? sanitize_pep723_name(meta.name) :
                                 (!first_cap_name.empty() ? first_cap_name : fs::path(path).stem().string());
    const std::string out_type = !meta.type.empty() ? meta.type : first_cap_type;

    return json{
        {"ok", true},
        {"name", out_name},
        {"description", meta.description},
        {"author", meta.author},
        {"version", meta.version},
        {"type", out_type},
        {"capabilities", std::move(caps_json)},
        {"requested_permissions", std::move(requested_permissions)},
        {"error", nullptr},
    }.dump();
}

} // namespace orcamicro
