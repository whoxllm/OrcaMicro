// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Headless CAD session and STEP import bridge over OrcaSlicer's CadDocument / GeometryEngine.
// Runs inside the :cad process so interactive modelling is isolated from slicing and UI.

#include "cad.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <exception>
#include <fstream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include <boost/filesystem.hpp>
#include <nlohmann/json.hpp>

#include "libslic3r/CAD/CadDocument.hpp"
#include "libslic3r/CAD/GeometryEngine.hpp"
#include "libslic3r/CAD/SketchEngine.hpp"
#include "libslic3r/Format/STL.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace orcamicro {
namespace {

using json = nlohmann::json;
namespace fs = boost::filesystem;
using namespace Slic3r;

std::mutex g_cad_mutex;
CadDocument g_doc;
std::string g_session_dir;
std::string g_recipe_path;

class CadError : public std::runtime_error {
public:
    explicit CadError(const std::string &msg) : std::runtime_error(msg) {}
};

BooleanMode parse_boolean_mode(const std::string &s)
{
    if (s == "add") return BooleanMode::Add;
    if (s == "cut") return BooleanMode::Cut;
    if (s == "intersect") return BooleanMode::Intersect;
    return BooleanMode::New;
}

std::string boolean_mode_name(BooleanMode m)
{
    switch (m) {
        case BooleanMode::New: return "new";
        case BooleanMode::Add: return "add";
        case BooleanMode::Cut: return "cut";
        case BooleanMode::Intersect: return "intersect";
    }
    return "new";
}

FaceGroup parse_face_group(const std::string &s)
{
    if (s == "top") return FaceGroup::Top;
    if (s == "bottom") return FaceGroup::Bottom;
    if (s == "lateral") return FaceGroup::Lateral;
    return FaceGroup::All;
}

std::string face_group_name(FaceGroup fg)
{
    switch (fg) {
        case FaceGroup::Top: return "top";
        case FaceGroup::Bottom: return "bottom";
        case FaceGroup::Lateral: return "lateral";
        case FaceGroup::All: return "all";
    }
    return "all";
}

std::string feature_type_name(CadFeatureType t)
{
    switch (t) {
        case CadFeatureType::Sketch: return "sketch";
        case CadFeatureType::Extrude: return "extrude";
        case CadFeatureType::Fillet: return "fillet";
        case CadFeatureType::Chamfer: return "chamfer";
        case CadFeatureType::Hole: return "hole";
        case CadFeatureType::Boolean: return "boolean";
        case CadFeatureType::Import: return "import";
        case CadFeatureType::Shell: return "shell";
        case CadFeatureType::Revolve: return "revolve";
        case CadFeatureType::Sweep: return "sweep";
        case CadFeatureType::Loft: return "loft";
        case CadFeatureType::Pattern: return "pattern";
        case CadFeatureType::Plane: return "plane";
        case CadFeatureType::Draft: return "draft";
        case CadFeatureType::Thread: return "thread";
        case CadFeatureType::Cut: return "cut";
        case CadFeatureType::Mirror: return "mirror";
        default: return "other";
    }
}

SketchPointRole parse_point_role(const std::string &s)
{
    if (s == "p1") return SketchPointRole::P1;
    if (s == "center") return SketchPointRole::Center;
    return SketchPointRole::P0;
}

std::string point_role_name(SketchPointRole r)
{
    switch (r) {
        case SketchPointRole::P0: return "p0";
        case SketchPointRole::P1: return "p1";
        case SketchPointRole::Center: return "center";
    }
    return "p0";
}

SketchConstraintType parse_constraint_type(const std::string &s)
{
    if (s == "fix") return SketchConstraintType::Fix;
    if (s == "coincident") return SketchConstraintType::Coincident;
    if (s == "horizontal") return SketchConstraintType::Horizontal;
    if (s == "vertical") return SketchConstraintType::Vertical;
    if (s == "distance") return SketchConstraintType::Distance;
    if (s == "distance_x") return SketchConstraintType::DistanceX;
    if (s == "distance_y") return SketchConstraintType::DistanceY;
    if (s == "equal_length") return SketchConstraintType::EqualLength;
    if (s == "equal_radius") return SketchConstraintType::EqualRadius;
    if (s == "parallel") return SketchConstraintType::Parallel;
    if (s == "perpendicular") return SketchConstraintType::Perpendicular;
    if (s == "concentric") return SketchConstraintType::Concentric;
    if (s == "tangent") return SketchConstraintType::Tangent;
    if (s == "midpoint") return SketchConstraintType::Midpoint;
    if (s == "angle") return SketchConstraintType::Angle;
    if (s == "radius") return SketchConstraintType::Radius;
    if (s == "diameter") return SketchConstraintType::Diameter;
    if (s == "collinear") return SketchConstraintType::Collinear;
    throw CadError("Unknown sketch constraint type: " + s);
}

std::string constraint_type_name(SketchConstraintType t)
{
    switch (t) {
        case SketchConstraintType::Fix: return "fix";
        case SketchConstraintType::Coincident: return "coincident";
        case SketchConstraintType::Horizontal: return "horizontal";
        case SketchConstraintType::Vertical: return "vertical";
        case SketchConstraintType::Distance: return "distance";
        case SketchConstraintType::DistanceX: return "distance_x";
        case SketchConstraintType::DistanceY: return "distance_y";
        case SketchConstraintType::EqualLength: return "equal_length";
        case SketchConstraintType::EqualRadius: return "equal_radius";
        case SketchConstraintType::Parallel: return "parallel";
        case SketchConstraintType::Perpendicular: return "perpendicular";
        case SketchConstraintType::Concentric: return "concentric";
        case SketchConstraintType::Tangent: return "tangent";
        case SketchConstraintType::Midpoint: return "midpoint";
        case SketchConstraintType::Angle: return "angle";
        case SketchConstraintType::Radius: return "radius";
        case SketchConstraintType::Diameter: return "diameter";
        case SketchConstraintType::Collinear: return "collinear";
        default: return "other";
    }
}

void check_topo_generation(const json &req)
{
    if (req.contains("topo_generation")) {
        const uint64_t expected = req.at("topo_generation").get<uint64_t>();
        if (expected != g_doc.topo_generation) {
            throw CadError("Geometry changed since selection; please pick again.");
        }
    }
}

SketchPlane parse_plane(const json &req)
{
    const std::string kind = req.value("plane", std::string("XY"));
    if (kind == "XZ") return SketchPlane::XZ();
    if (kind == "YZ") return SketchPlane::YZ();
    if (kind == "face") {
        check_topo_generation(req);
        const int body_idx = req.value("body_index", 0);
        const int face_id = req.value("face_id", -1);
        SketchPlane out;
        if (!g_doc.plane_of_face(body_idx, face_id, out))
            throw CadError("Selected face is not planar or no longer valid.");
        return out;
    }
    if (req.contains("plane_origin") && req.contains("plane_normal") &&
        req.contains("plane_x") && req.contains("plane_y")) {
        auto v3 = [](const json &a) {
            return Vec3d(a.at(0).get<double>(), a.at(1).get<double>(), a.at(2).get<double>());
        };
        return SketchPlane{
            v3(req.at("plane_origin")),
            v3(req.at("plane_normal")),
            v3(req.at("plane_x")),
            v3(req.at("plane_y"))
        };
    }
    return SketchPlane::XY();
}

json plane_to_json(const SketchPlane &p)
{
    return json{
        {"origin", {p.origin.x(), p.origin.y(), p.origin.z()}},
        {"normal", {p.normal.x(), p.normal.y(), p.normal.z()}},
        {"x_axis", {p.x_axis.x(), p.x_axis.y(), p.x_axis.z()}},
        {"y_axis", {p.y_axis.x(), p.y_axis.y(), p.y_axis.z()}}
    };
}

std::vector<SketchEntity> parse_entities(const json &arr)
{
    std::vector<SketchEntity> out;
    out.reserve(arr.size());
    for (const auto &item : arr) {
        SketchEntity e;
        const std::string t = item.value("type", std::string("line"));
        if (t == "arc") e.type = SketchEntity::Type::Arc;
        else if (t == "circle") e.type = SketchEntity::Type::Circle;
        else if (t == "point") e.type = SketchEntity::Type::Point;
        else e.type = SketchEntity::Type::Line;

        if (item.contains("p0")) e.p0 = Vec2d(item["p0"].at(0).get<double>(), item["p0"].at(1).get<double>());
        if (item.contains("p1")) e.p1 = Vec2d(item["p1"].at(0).get<double>(), item["p1"].at(1).get<double>());
        if (item.contains("center")) e.center = Vec2d(item["center"].at(0).get<double>(), item["center"].at(1).get<double>());
        e.radius = item.value("radius", 0.0);
        e.start_angle = item.value("start_angle", 0.0);
        e.end_angle = item.value("end_angle", 0.0);
        e.construction = item.value("construction", false);
        if (e.type == SketchEntity::Type::Circle) {
            e.p0 = e.center;
        }
        out.push_back(e);
    }
    return out;
}

json entities_to_json(const std::vector<SketchEntity> &entities)
{
    json arr = json::array();
    for (const auto &e : entities) {
        std::string t = "line";
        if (e.type == SketchEntity::Type::Arc) t = "arc";
        else if (e.type == SketchEntity::Type::Circle) t = "circle";
        else if (e.type == SketchEntity::Type::Point) t = "point";
        arr.push_back(json{
            {"type", t},
            {"p0", {e.p0.x(), e.p0.y()}},
            {"p1", {e.p1.x(), e.p1.y()}},
            {"center", {e.center.x(), e.center.y()}},
            {"radius", e.radius},
            {"start_angle", e.start_angle},
            {"end_angle", e.end_angle},
            {"construction", e.construction}
        });
    }
    return arr;
}

std::vector<SketchEntityConstraintDef> parse_constraints(const json &arr)
{
    std::vector<SketchEntityConstraintDef> out;
    out.reserve(arr.size());
    for (const auto &item : arr) {
        SketchEntityConstraintDef c;
        c.type = parse_constraint_type(item.at("type").get<std::string>());
        c.ea = item.value("ea", -1);
        c.eb = item.value("eb", -1);
        c.ec = item.value("ec", -1);
        c.ra = parse_point_role(item.value("ra", std::string("p0")));
        c.rb = parse_point_role(item.value("rb", std::string("p0")));
        c.rc = parse_point_role(item.value("rc", std::string("p0")));
        c.value = item.value("value", 0.0);
        out.push_back(c);
    }
    return out;
}

json constraints_to_json(const std::vector<SketchEntityConstraintDef> &constraints)
{
    json arr = json::array();
    for (const auto &c : constraints) {
        arr.push_back(json{
            {"type", constraint_type_name(c.type)},
            {"ea", c.ea},
            {"eb", c.eb},
            {"ec", c.ec},
            {"ra", point_role_name(c.ra)},
            {"rb", point_role_name(c.rb)},
            {"rc", point_role_name(c.rc)},
            {"value", c.value}
        });
    }
    return arr;
}

void write_bytes_atomic(const std::string &path, const std::string &bytes)
{
    if (path.empty()) return;
    const fs::path target(path);
    if (target.has_parent_path()) fs::create_directories(target.parent_path());
    const fs::path tmp = target.string() + ".tmp";
    {
        std::ofstream out(tmp.string(), std::ios::binary | std::ios::trunc);
        if (!out) throw CadError("Cannot write file: " + path);
        out.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
        if (!out) throw CadError("Failed writing file: " + path);
    }
    fs::rename(tmp, target);
}

// Writes compact binary mesh + B-rep edge segments for GL rendering and picking:
//   Header: "OCAD" (4B) | version=1 (u32) | tri_count (u32) | edge_seg_count (u32)
//   Triangles (tri_count * 44B): 9x float32 (v0,v1,v2) + int32 body_id + int32 face_id
//   Edge segs (edge_seg_count * 32B): 6x float32 (p0,p1) + int32 body_id + int32 edge_id
std::string write_display_mesh()
{
    if (g_session_dir.empty()) return {};
    fs::create_directories(g_session_dir);
    const std::string out_path = (fs::path(g_session_dir) / "display.cadmesh").string();
    const std::string tmp_path = out_path + ".tmp";

    struct EdgeSeg {
        float p0[3];
        float p1[3];
        int32_t body_id;
        int32_t edge_id;
    };
    std::vector<EdgeSeg> edge_segs;
    for (int bi = 0; bi < static_cast<int>(g_doc.bodies.size()); ++bi) {
        const auto edges = GeometryEngine::edges_of(g_doc.bodies[bi].shape);
        for (int ei = 0; ei < static_cast<int>(edges.size()); ++ei) {
            const auto poly = GeometryEngine::sample_edge_world(edges[ei], 0.1);
            for (size_t k = 1; k < poly.size(); ++k) {
                EdgeSeg seg;
                seg.p0[0] = static_cast<float>(poly[k - 1].x());
                seg.p0[1] = static_cast<float>(poly[k - 1].y());
                seg.p0[2] = static_cast<float>(poly[k - 1].z());
                seg.p1[0] = static_cast<float>(poly[k].x());
                seg.p1[1] = static_cast<float>(poly[k].y());
                seg.p1[2] = static_cast<float>(poly[k].z());
                seg.body_id = bi;
                seg.edge_id = ei;
                edge_segs.push_back(seg);
            }
        }
    }

    const auto &its = g_doc.display_mesh.its;
    const uint32_t tri_count = static_cast<uint32_t>(its.indices.size());
    const uint32_t edge_seg_count = static_cast<uint32_t>(edge_segs.size());
    const uint32_t version = 1;

    std::ofstream out(tmp_path, std::ios::binary | std::ios::trunc);
    if (!out) throw CadError("Cannot write CAD mesh file");
    out.write("OCAD", 4);
    out.write(reinterpret_cast<const char *>(&version), 4);
    out.write(reinterpret_cast<const char *>(&tri_count), 4);
    out.write(reinterpret_cast<const char *>(&edge_seg_count), 4);

    for (uint32_t i = 0; i < tri_count; ++i) {
        const auto &idx = its.indices[i];
        const auto &v0 = its.vertices[idx[0]];
        const auto &v1 = its.vertices[idx[1]];
        const auto &v2 = its.vertices[idx[2]];
        const float verts[9] = {
            v0.x(), v0.y(), v0.z(),
            v1.x(), v1.y(), v1.z(),
            v2.x(), v2.y(), v2.z()
        };
        const int32_t body_id = (i < g_doc.display_tri_body.size()) ? g_doc.display_tri_body[i] : 0;
        const int32_t face_id = (i < g_doc.display_tri_face.size()) ? g_doc.display_tri_face[i] : -1;
        out.write(reinterpret_cast<const char *>(verts), sizeof(verts));
        out.write(reinterpret_cast<const char *>(&body_id), 4);
        out.write(reinterpret_cast<const char *>(&face_id), 4);
    }

    for (const auto &seg : edge_segs) {
        out.write(reinterpret_cast<const char *>(seg.p0), 12);
        out.write(reinterpret_cast<const char *>(seg.p1), 12);
        out.write(reinterpret_cast<const char *>(&seg.body_id), 4);
        out.write(reinterpret_cast<const char *>(&seg.edge_id), 4);
    }
    out.close();
    if (!out) throw CadError("Failed flushing CAD mesh file");
    fs::rename(tmp_path, out_path);
    return out_path;
}

void auto_save_recipe()
{
    if (g_recipe_path.empty()) return;
    write_bytes_atomic(g_recipe_path, g_doc.serialize_recipe());
}

json build_state_response(const std::string &mesh_path)
{
    json features = json::array();
    for (int i = 0; i < static_cast<int>(g_doc.features.size()); ++i) {
        const auto &f = g_doc.features[i];
        json jf{
            {"index", i},
            {"type", feature_type_name(f.type)},
            {"name", f.name},
            {"enabled", f.enabled}
        };
        switch (f.type) {
            case CadFeatureType::Sketch:
                jf["plane"] = plane_to_json(f.plane);
                jf["shape"] = (f.shape == SketchShape::Circle) ? "circle" : "rectangle";
                jf["width"] = f.width;
                jf["height"] = f.height;
                jf["radius"] = f.radius;
                jf["entities"] = entities_to_json(f.entities);
                jf["constraints"] = constraints_to_json(f.entity_constraints);
                break;
            case CadFeatureType::Extrude:
                jf["sketch_ref"] = f.sketch_ref;
                jf["distance"] = f.distance;
                jf["symmetric"] = f.symmetric;
                jf["flip"] = f.flip;
                jf["mode"] = boolean_mode_name(f.mode);
                jf["target_body"] = f.target_body;
                break;
            case CadFeatureType::Fillet:
            case CadFeatureType::Chamfer:
                jf["size"] = f.dressup_size;
                jf["face_group"] = face_group_name(f.face_group);
                jf["edge_ids"] = f.dressup_edge_ids();
                jf["target_body"] = f.target_body;
                break;
            case CadFeatureType::Hole:
                jf["diameter"] = f.hole_diameter;
                jf["depth"] = f.hole_depth;
                jf["through"] = f.hole_through;
                jf["x"] = f.hole_x;
                jf["y"] = f.hole_y;
                jf["plane"] = plane_to_json(f.plane);
                jf["target_body"] = f.target_body;
                break;
            case CadFeatureType::Boolean:
                jf["mode"] = boolean_mode_name(f.mode);
                jf["target_body"] = f.target_body;
                jf["tool_body"] = f.bool_tool_body;
                jf["keep_tool"] = f.bool_keep_tool;
                break;
            default:
                break;
        }
        features.push_back(std::move(jf));
    }

    json bodies = json::array();
    double total_volume = 0.0;
    for (int i = 0; i < static_cast<int>(g_doc.bodies.size()); ++i) {
        const auto &b = g_doc.bodies[i];
        const auto mp = g_doc.body_mass_properties(i);
        if (mp.valid && mp.is_solid) total_volume += mp.volume;
        bodies.push_back(json{
            {"index", i},
            {"name", b.has_user_name ? b.user_name : b.name},
            {"volume", mp.valid ? mp.volume : 0.0},
            {"surface_area", mp.valid ? mp.surface_area : 0.0},
            {"face_count", GeometryEngine::face_count(b.shape)},
            {"edge_count", GeometryEngine::edge_count(b.shape)}
        });
    }

    json bounds = nullptr;
    if (!g_doc.display_mesh.empty()) {
        const auto bb = g_doc.display_mesh.bounding_box();
        bounds = json{
            {"min", {bb.min.x(), bb.min.y(), bb.min.z()}},
            {"max", {bb.max.x(), bb.max.y(), bb.max.z()}}
        };
    }

    return json{
        {"ok", true},
        {"topo_generation", g_doc.topo_generation},
        {"can_undo", g_doc.can_undo()},
        {"can_redo", g_doc.can_redo()},
        {"mesh_path", mesh_path},
        {"triangle_count", static_cast<int>(g_doc.display_mesh.facets_count())},
        {"body_count", static_cast<int>(g_doc.bodies.size())},
        {"volume_mm3", total_volume},
        {"bounds", bounds},
        {"features", features},
        {"bodies", bodies}
    };
}

// Saves g_doc.features and checkpoints before a mutation. If the mutation or recompute fails
// (or throws), restores features, drops the checkpoint via abandon_checkpoint() (so the redo
// stack is not polluted with the failed state), and recomputes back to the prior valid bodies.
struct FeatureTransaction {
    std::vector<CadFeature> saved;
    bool committed{false};

    FeatureTransaction() : saved(g_doc.features) { g_doc.checkpoint(); }
    ~FeatureTransaction() {
        if (!committed) {
            g_doc.features = std::move(saved);
            g_doc.abandon_checkpoint();
            g_doc.recompute();
        }
    }

    json commit() {
        if (!g_doc.recompute()) {
            const std::string err = g_doc.error.empty() ? "CAD recompute failed" : g_doc.error;
            throw CadError(err);
        }
        committed = true;
        const std::string mesh_path = write_display_mesh();
        auto_save_recipe();
        return build_state_response(mesh_path);
    }
};

json handle_step_to_stl(const json &req)
{
    const std::string step_path = req.at("step_path").get<std::string>();
    const std::string stl_path = req.at("stl_path").get<std::string>();
    const double lin_defl = req.value("linear_deflection", 0.01);
    const double ang_defl = req.value("angular_deflection", 0.5);

    std::string err;
    const auto solids = GeometryEngine::read_step_solids(step_path, err);
    if (solids.empty()) {
        throw CadError(err.empty() ? "STEP file contains no solid geometry" : err);
    }
    TriangleMesh combined;
    for (const auto &s : solids) {
        TriangleMesh part = GeometryEngine::tessellate(s, lin_defl, ang_defl);
        if (!part.empty()) combined.merge(part);
    }
    if (combined.empty()) {
        throw CadError("STEP tessellation produced an empty mesh");
    }
    const fs::path target(stl_path);
    if (target.has_parent_path()) fs::create_directories(target.parent_path());
    if (!store_stl(stl_path.c_str(), &combined, true)) {
        throw CadError("Failed to write converted STL: " + stl_path);
    }
    return json{
        {"ok", true},
        {"stl_path", stl_path},
        {"triangle_count", static_cast<int>(combined.facets_count())},
        {"solid_count", static_cast<int>(solids.size())}
    };
}

json run_cad_command(const json &req)
{
    const std::string op = req.at("op").get<std::string>();

    if (op == "step_to_stl") {
        return handle_step_to_stl(req);
    }

    if (op == "open") {
        g_session_dir = req.value("session_dir", std::string());
        g_recipe_path = req.value("recipe_path", std::string());
        g_doc.clear();
        // Slightly coarser than desktop 0.003 for snappy phone tessellation while staying smooth.
        g_doc.linear_deflection = req.value("linear_deflection", 0.02);
        g_doc.angular_deflection = req.value("angular_deflection", 0.5);

        if (!g_recipe_path.empty() && fs::exists(g_recipe_path) && fs::file_size(g_recipe_path) > 0) {
            std::ifstream in(g_recipe_path, std::ios::binary);
            std::string blob((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
            if (!g_doc.deserialize_recipe(blob)) {
                throw CadError("Could not read saved CAD recipe");
            }
            if (!g_doc.recompute()) {
                throw CadError(g_doc.error.empty() ? "Could not rebuild CAD recipe" : g_doc.error);
            }
        }
        const std::string mesh_path = write_display_mesh();
        return build_state_response(mesh_path);
    }

    if (op == "save") {
        const std::string path = req.value("recipe_path", g_recipe_path);
        if (path.empty()) throw CadError("No recipe_path specified");
        write_bytes_atomic(path, g_doc.serialize_recipe());
        return json{{"ok", true}, {"recipe_path", path}};
    }

    if (op == "export_stl") {
        const std::string stl_path = req.at("stl_path").get<std::string>();
        if (g_doc.display_mesh.empty()) throw CadError("Nothing to export: the CAD document has no solid body.");
        const fs::path target(stl_path);
        if (target.has_parent_path()) fs::create_directories(target.parent_path());
        TriangleMesh copy = g_doc.display_mesh;
        if (!store_stl(stl_path.c_str(), &copy, true)) {
            throw CadError("Failed to write STL file: " + stl_path);
        }
        return json{
            {"ok", true},
            {"stl_path", stl_path},
            {"triangle_count", static_cast<int>(copy.facets_count())}
        };
    }

    if (op == "export_step") {
        const std::string step_path = req.at("step_path").get<std::string>();
        if (g_doc.bodies.empty()) throw CadError("Nothing to export: the CAD document has no solid body.");
        const fs::path target(step_path);
        if (target.has_parent_path()) fs::create_directories(target.parent_path());
        std::string err;
        if (!g_doc.export_step(step_path, {}, err)) {
            throw CadError(err.empty() ? "Failed to export STEP file" : err);
        }
        return json{{"ok", true}, {"step_path", step_path}};
    }

    if (op == "undo") {
        if (!g_doc.undo()) return build_state_response(write_display_mesh());
        const std::string mesh_path = write_display_mesh();
        auto_save_recipe();
        return build_state_response(mesh_path);
    }

    if (op == "redo") {
        if (!g_doc.redo()) return build_state_response(write_display_mesh());
        const std::string mesh_path = write_display_mesh();
        auto_save_recipe();
        return build_state_response(mesh_path);
    }

    if (op == "add_sketch") {
        const SketchPlane plane = parse_plane(req);
        const std::string name = req.value("name", "Sketch " + std::to_string(g_doc.features.size() + 1));
        const bool has_entities = req.contains("entities") && req.at("entities").is_array() && !req.at("entities").empty();
        auto entities = has_entities ? parse_entities(req.at("entities")) : std::vector<SketchEntity>{};
        auto constraints = (has_entities && req.contains("constraints")) ? parse_constraints(req.at("constraints"))
                                                                         : std::vector<SketchEntityConstraintDef>{};
        FeatureTransaction tx;
        int idx = -1;
        if (has_entities) {
            idx = g_doc.add_sketch_entities(entities, plane, name, constraints);
            if (!constraints.empty() && !g_doc.solve_sketch_feature(idx)) {
                throw CadError("Sketch constraints could not be solved (over-constrained or conflicting).");
            }
        } else {
            const std::string shape_str = req.value("shape", std::string("rectangle"));
            const SketchShape shape = (shape_str == "circle") ? SketchShape::Circle : SketchShape::Rectangle;
            const double w = req.value("width", 20.0);
            const double h = req.value("height", 20.0);
            const double r = req.value("radius", 10.0);
            idx = g_doc.add_sketch(shape, plane, w, h, r, name);
        }
        json resp = tx.commit();
        resp["feature_index"] = idx;
        return resp;
    }

    if (op == "update_sketch") {
        const int idx = req.at("index").get<int>();
        if (idx < 0 || idx >= static_cast<int>(g_doc.features.size()) ||
            g_doc.features[idx].type != CadFeatureType::Sketch) {
            throw CadError("Invalid sketch feature index");
        }
        CadFeature edited = g_doc.features[idx];
        if (req.contains("entities")) edited.entities = parse_entities(req.at("entities"));
        if (req.contains("constraints")) edited.entity_constraints = parse_constraints(req.at("constraints"));
        if (!edited.entity_constraints.empty()) {
            if (!solve_sketch_entities(edited.entities, edited.entity_constraints)) {
                throw CadError("Sketch constraints could not be solved (over-constrained or conflicting).");
            }
        }
        g_doc.checkpoint();
        if (!g_doc.replace_feature(idx, edited)) {
            g_doc.abandon_checkpoint();
            throw CadError(g_doc.error.empty() ? "Could not update sketch" : g_doc.error);
        }
        const std::string mesh_path = write_display_mesh();
        auto_save_recipe();
        return build_state_response(mesh_path);
    }

    if (op == "add_extrude") {
        const int sketch_ref = req.at("sketch_ref").get<int>();
        const double distance = req.at("distance").get<double>();
        const bool symmetric = req.value("symmetric", false);
        const bool flip = req.value("flip", false);
        const BooleanMode mode = parse_boolean_mode(req.value("mode", std::string("new")));
        const int target_body = req.value("target_body", -1);
        const std::string name = req.value("name", "Extrude " + std::to_string(g_doc.features.size() + 1));

        FeatureTransaction tx;
        const int idx = g_doc.add_extrude(sketch_ref, distance, symmetric, mode, name);
        g_doc.features[idx].flip = flip;
        g_doc.features[idx].target_body = target_body;
        json resp = tx.commit();
        resp["feature_index"] = idx;
        return resp;
    }

    if (op == "add_fillet" || op == "add_chamfer") {
        const bool is_fillet = (op == "add_fillet");
        const double size = req.at(is_fillet ? "radius" : "distance").get<double>();
        const int target_body = req.value("target_body", -1);
        const std::string name = req.value("name", (is_fillet ? "Fillet " : "Chamfer ") + std::to_string(g_doc.features.size() + 1));
        std::vector<int> edge_ids;
        if (req.contains("edge_ids") && req.at("edge_ids").is_array()) {
            for (const auto &e : req.at("edge_ids")) edge_ids.push_back(e.get<int>());
        }
        if (!edge_ids.empty()) check_topo_generation(req);

        FeatureTransaction tx;
        int idx = -1;
        if (!edge_ids.empty()) {
            idx = is_fillet ? g_doc.add_fillet(size, edge_ids, name)
                            : g_doc.add_chamfer(size, edge_ids, name);
        } else {
            const FaceGroup fg = parse_face_group(req.value("face_group", std::string("all")));
            idx = is_fillet ? g_doc.add_fillet(size, fg, name)
                            : g_doc.add_chamfer(size, fg, name);
        }
        g_doc.features[idx].target_body = target_body;
        json resp = tx.commit();
        resp["feature_index"] = idx;
        return resp;
    }

    if (op == "add_hole") {
        const SketchPlane plane = parse_plane(req);
        const double diameter = req.at("diameter").get<double>();
        const double depth = req.value("depth", 10.0);
        const bool through = req.value("through", true);
        const double x = req.value("x", 0.0);
        const double y = req.value("y", 0.0);
        const int target_body = req.value("target_body", -1);
        const std::string name = req.value("name", "Hole " + std::to_string(g_doc.features.size() + 1));

        FeatureTransaction tx;
        const int idx = g_doc.add_hole(diameter, depth, through, x, y, plane, name);
        g_doc.features[idx].target_body = target_body;
        json resp = tx.commit();
        resp["feature_index"] = idx;
        return resp;
    }

    if (op == "add_boolean") {
        const BooleanMode mode = parse_boolean_mode(req.value("mode", std::string("add")));
        const int target_body = req.value("target_body", 0);
        const int tool_body = req.value("tool_body", 1);
        const bool keep_tool = req.value("keep_tool", false);
        const std::string name = req.value("name", "Boolean " + std::to_string(g_doc.features.size() + 1));

        FeatureTransaction tx;
        const int idx = g_doc.add_boolean(mode, target_body, tool_body, keep_tool, 0.0, -1, -1, name);
        json resp = tx.commit();
        resp["feature_index"] = idx;
        return resp;
    }

    if (op == "import_step") {
        const std::string step_path = req.at("step_path").get<std::string>();
        const std::string base_name = req.value("name", fs::path(step_path).stem().string());
        std::string err;
        const auto solids = GeometryEngine::read_step_solids(step_path, err);
        if (solids.empty()) {
            throw CadError(err.empty() ? "STEP file contains no solid geometry" : err);
        }
        FeatureTransaction tx;
        for (size_t i = 0; i < solids.size(); ++i) {
            CadFeature f;
            f.type = CadFeatureType::Import;
            f.name = solids.size() == 1 ? base_name : (base_name + " " + std::to_string(i + 1));
            f.imported_solid = solids[i];
            g_doc.features.push_back(std::move(f));
        }
        return tx.commit();
    }

    if (op == "remove_feature") {
        const int idx = req.at("index").get<int>();
        g_doc.checkpoint();
        if (!g_doc.remove_feature(idx)) {
            g_doc.abandon_checkpoint();
            throw CadError(g_doc.error.empty() ? "Could not remove feature" : g_doc.error);
        }
        const std::string mesh_path = write_display_mesh();
        auto_save_recipe();
        return build_state_response(mesh_path);
    }

    if (op == "set_feature_enabled") {
        const int idx = req.at("index").get<int>();
        const bool enabled = req.at("enabled").get<bool>();
        g_doc.checkpoint();
        if (!g_doc.set_feature_enabled(idx, enabled)) {
            g_doc.abandon_checkpoint();
            throw CadError(g_doc.error.empty() ? "Could not toggle feature" : g_doc.error);
        }
        const std::string mesh_path = write_display_mesh();
        auto_save_recipe();
        return build_state_response(mesh_path);
    }

    if (op == "move_feature") {
        const int idx = req.at("index").get<int>();
        const int delta = req.at("delta").get<int>();
        g_doc.checkpoint();
        if (!g_doc.move_feature(idx, delta)) {
            g_doc.abandon_checkpoint();
            throw CadError(g_doc.error.empty() ? "Could not move feature" : g_doc.error);
        }
        const std::string mesh_path = write_display_mesh();
        auto_save_recipe();
        return build_state_response(mesh_path);
    }

    if (op == "edit_feature") {
        const int idx = req.at("index").get<int>();
        if (idx < 0 || idx >= static_cast<int>(g_doc.features.size())) {
            throw CadError("Invalid feature index");
        }
        CadFeature edited = g_doc.features[idx];
        switch (edited.type) {
            case CadFeatureType::Sketch:
                if (req.contains("width")) edited.width = req.at("width").get<double>();
                if (req.contains("height")) edited.height = req.at("height").get<double>();
                if (req.contains("radius")) edited.radius = req.at("radius").get<double>();
                break;
            case CadFeatureType::Extrude:
                if (req.contains("distance")) edited.distance = req.at("distance").get<double>();
                if (req.contains("symmetric")) edited.symmetric = req.at("symmetric").get<bool>();
                if (req.contains("flip")) edited.flip = req.at("flip").get<bool>();
                if (req.contains("mode")) edited.mode = parse_boolean_mode(req.at("mode").get<std::string>());
                break;
            case CadFeatureType::Fillet:
            case CadFeatureType::Chamfer:
                if (req.contains("size")) edited.dressup_size = req.at("size").get<double>();
                break;
            case CadFeatureType::Hole:
                if (req.contains("diameter")) edited.hole_diameter = req.at("diameter").get<double>();
                if (req.contains("depth")) edited.hole_depth = req.at("depth").get<double>();
                if (req.contains("through")) edited.hole_through = req.at("through").get<bool>();
                if (req.contains("x")) edited.hole_x = req.at("x").get<double>();
                if (req.contains("y")) edited.hole_y = req.at("y").get<double>();
                break;
            default:
                break;
        }
        g_doc.checkpoint();
        if (!g_doc.replace_feature(idx, edited)) {
            g_doc.abandon_checkpoint();
            throw CadError(g_doc.error.empty() ? "Could not edit feature" : g_doc.error);
        }
        const std::string mesh_path = write_display_mesh();
        auto_save_recipe();
        return build_state_response(mesh_path);
    }

    throw CadError("Unknown CAD operation: " + op);
}

} // namespace

std::string cad_command(const std::string &request_json)
{
    std::lock_guard<std::mutex> lock(g_cad_mutex);
    json response;
    try {
        response = run_cad_command(json::parse(request_json));
    } catch (const CadError &e) {
        response = json{{"ok", false}, {"error", e.what()}};
    } catch (const json::exception &e) {
        response = json{{"ok", false}, {"error", std::string("Bad CAD request: ") + e.what()}};
    } catch (const std::exception &e) {
        response = json{{"ok", false}, {"error", e.what()}};
    } catch (...) {
        response = json{{"ok", false}, {"error", "Unknown CAD engine error"}};
    }
    return response.dump();
}

} // namespace orcamicro
