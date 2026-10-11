// JNI entry points of libopenscad.so for app.orcaandroid.scad.ScadNative. The app loads the library
// only in its separate ":scad" process (ScadService), so a crash or a runaway design never takes the
// slicer down. Everything goes through the C API in scad_bridge.h.
#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>

#include "scad_bridge.h"

namespace {

std::string str(JNIEnv *env, jstring s)
{
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// NewStringUTF wants modified UTF-8; OpenSCAD's log is plain UTF-8, so go through a byte array.
jstring jstr(JNIEnv *env, const std::string &s)
{
    jbyteArray bytes = env->NewByteArray(jsize(s.size()));
    env->SetByteArrayRegion(bytes, 0, jsize(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    jclass cls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    jstring utf8 = env->NewStringUTF("UTF-8");
    auto out = static_cast<jstring>(env->NewObject(cls, ctor, bytes, utf8));
    env->DeleteLocalRef(utf8);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(cls);
    return out;
}

void json_escape(std::string &out, const char *s)
{
    for (; *s; ++s) {
        const auto c = static_cast<unsigned char>(*s);
        switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
            if (c < 0x20) {
                char buf[8];
                snprintf(buf, sizeof buf, "\\u%04x", c);
                out += buf;
            } else {
                out += char(c);
            }
        }
    }
}

// Binary STL (little endian, as arm64), one facet per triangle with its computed normal.
bool write_stl(const char *path, const float *tris, size_t count)
{
    FILE *f = fopen(path, "wb");
    if (!f) return false;
    char header[80] = "OpenSCAD (OrcaStudio-Android)";
    const auto n = uint32_t(count);
    bool ok = fwrite(header, 1, 80, f) == 80 && fwrite(&n, 4, 1, f) == 1;
    for (size_t i = 0; ok && i < count; ++i) {
        const float *t = tris + i * 9;
        const float ux = t[3] - t[0], uy = t[4] - t[1], uz = t[5] - t[2];
        const float vx = t[6] - t[0], vy = t[7] - t[1], vz = t[8] - t[2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        const float len = std::sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0) { nx /= len; ny /= len; nz /= len; }
        const float facet[12] = {nx, ny, nz, t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8]};
        const uint16_t attr = 0;
        ok = fwrite(facet, sizeof facet, 1, f) == 1 && fwrite(&attr, 2, 1, f) == 1;
    }
    return fclose(f) == 0 && ok;
}

} // namespace

extern "C" {

// cache_dir: fontconfig writes its font cache there (no $HOME on Android).
JNIEXPORT jint JNICALL Java_app_orcaandroid_scad_ScadNative_init(JNIEnv *env, jobject, jstring resource_dir, jstring cache_dir)
{
    const std::string cache = str(env, cache_dir);
    setenv("HOME", cache.c_str(), 1);
    setenv("XDG_CACHE_HOME", cache.c_str(), 1);
    // text() can use the system fonts (Roboto, Noto) besides the bundled Liberation ones (FontCache.cc).
    setenv("OPENSCAD_FONT_PATH", "/system/fonts", 0);
    return scad_init(str(env, resource_dir).c_str());
}

// Renders to a binary STL at out_stl (only for a non-empty 3D result) and returns
// {"ok":bool,"dimension":0|2|3,"triangles":n,"log":"..."}.
JNIEXPORT jstring JNICALL Java_app_orcaandroid_scad_ScadNative_render(JNIEnv *env, jobject, jstring source, jstring filename,
                                                                       jstring params_json, jstring out_stl)
{
    const std::string params = str(env, params_json);
    scad_result *r = scad_render(str(env, source).c_str(), str(env, filename).c_str(), params.empty() ? nullptr : params.c_str());
    size_t count = 0;
    const float *tris = scad_result_triangles(r, &count);
    bool ok = scad_result_ok(r) != 0;
    std::string log = scad_result_log(r);
    if (ok && tris && count > 0 && !write_stl(str(env, out_stl).c_str(), tris, count)) {
        ok = false;
        log += "ERROR: could not write the mesh\n";
    }
    std::string out = "{\"ok\":";
    out += ok ? "true" : "false";
    out += ",\"dimension\":" + std::to_string(scad_result_dimension(r));
    out += ",\"triangles\":" + std::to_string(count);
    out += ",\"log\":\"";
    json_escape(out, log.c_str());
    out += "\"}";
    scad_result_free(r);
    return jstr(env, out);
}

// The Customizer parameters (OpenSCAD's --export-format param JSON), or null on a syntax error.
JNIEXPORT jstring JNICALL Java_app_orcaandroid_scad_ScadNative_parameters(JNIEnv *env, jobject, jstring source, jstring filename)
{
    char *p = scad_parameters(str(env, source).c_str(), str(env, filename).c_str());
    if (!p) return nullptr;
    jstring out = jstr(env, p);
    scad_free_string(p);
    return out;
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_scad_ScadNative_version(JNIEnv *env, jobject)
{
    return jstr(env, scad_version());
}

} // extern "C"
