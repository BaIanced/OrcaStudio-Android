// C API of libopenscad.so: OpenSCAD's language and geometry core, built headless for Android.
//
// The library is separate from liborca_jni.so and exports only these functions, so its own copies
// of Boost, CGAL, Clipper2 and the C++ runtime never meet libslic3r's. Nothing C++ crosses this
// boundary. Calls are not reentrant: OpenSCAD keeps global state (caches, output handler), so the
// caller serialises them.
#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SCAD_API __attribute__((visibility("default")))

typedef struct scad_result scad_result;

// Once, before anything else. resource_dir holds OpenSCAD's resources (libraries/, fonts/); it may
// be empty. Returns 0 on success.
SCAD_API int scad_init(const char *resource_dir);

// Parses and renders `source`. `filename` is the path the source is treated as coming from
// (include/use/import resolve against its folder; may be empty). `params_json` is a flat JSON
// object of Customizer values ({"width": 20, "label": "abc"}) or NULL. Always returns a result;
// check scad_result_ok().
SCAD_API scad_result *scad_render(const char *source, const char *filename, const char *params_json);

// The Customizer parameters of `source` as OpenSCAD's own JSON export (`--export-format param`).
// Returned string is owned by the caller: free it with scad_free_string().
SCAD_API char *scad_parameters(const char *source, const char *filename);

SCAD_API int scad_result_ok(const scad_result *r);
// Triangle soup: 9 floats (three xyz vertices) per triangle, counter-clockwise seen from outside.
SCAD_API const float *scad_result_triangles(const scad_result *r, size_t *triangle_count);
// All messages (echo, warnings, errors) of the run, newline separated, UTF-8.
SCAD_API const char *scad_result_log(const scad_result *r);
// 2 or 3, or 0 when nothing was rendered. A 2D result has no triangles.
SCAD_API int scad_result_dimension(const scad_result *r);
SCAD_API void scad_result_free(scad_result *r);

SCAD_API void scad_free_string(char *s);
SCAD_API const char *scad_version(void);

#ifdef __cplusplus
}
#endif
