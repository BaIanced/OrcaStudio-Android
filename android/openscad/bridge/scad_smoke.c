// Link and run check for libopenscad.so: renders a small CSG design and a Customizer parameter.
// Usage: scad_smoke [resource_dir]. Exit code 0 when both renders produce triangles.
#include <stdio.h>

#include "scad_bridge.h"

static const char *kSource =
    "// [Size]\n"
    "size = 20; // [5:50]\n"
    "difference() {\n"
    "  cube(size, center = true);\n"
    "  sphere(r = size * 0.65, $fn = 64);\n"
    "}\n"
    "echo(str(\"size=\", size));\n";

static size_t render(const char *params)
{
    scad_result *r = scad_render(kSource, "", params);
    size_t n = 0;
    scad_result_triangles(r, &n);
    printf("params=%s ok=%d dim=%d triangles=%zu\n%s", params ? params : "(none)", scad_result_ok(r),
           scad_result_dimension(r), n, scad_result_log(r));
    if (!scad_result_ok(r)) n = 0;
    scad_result_free(r);
    return n;
}

int main(int argc, char **argv)
{
    if (scad_init(argc > 1 ? argv[1] : "") != 0) {
        puts("scad_init failed");
        return 1;
    }
    printf("OpenSCAD %s\n", scad_version());
    char *params = scad_parameters(kSource, "");
    printf("parameters: %s\n", params ? params : "(null)");
    scad_free_string(params);
    const size_t a = render(NULL);
    const size_t b = render("{\"size\": 30}");
    return a > 0 && b > 0 ? 0 : 1;
}
