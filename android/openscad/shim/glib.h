// The part of GLib that OpenSCAD's core uses (src/core/str_utf8_wrapper.h, UnicodeIdentifier.cc,
// Value.cc, FreetypeRenderer.cc, LibraryInfo.cc): a few UTF-8 helpers and NFC normalisation.
// GLib itself isn't built for Android; these follow GLib's documented behaviour, with
// normalisation from utf8proc.
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

#include <utf8proc.h>

#define GLIB_MAJOR_VERSION 2
#define GLIB_MINOR_VERSION 80
#define GLIB_MICRO_VERSION 0

typedef char gchar;
typedef int gint;
typedef int gboolean;
typedef long glong;
typedef size_t gsize;
typedef ssize_t gssize;
typedef uint32_t gunichar;

typedef enum { G_NORMALIZE_DEFAULT, G_NORMALIZE_DEFAULT_COMPOSE, G_NORMALIZE_ALL, G_NORMALIZE_ALL_COMPOSE } GNormalizeMode;
#define G_NORMALIZE_NFD G_NORMALIZE_DEFAULT
#define G_NORMALIZE_NFC G_NORMALIZE_DEFAULT_COMPOSE
#define G_NORMALIZE_NFKD G_NORMALIZE_ALL
#define G_NORMALIZE_NFKC G_NORMALIZE_ALL_COMPOSE

#ifdef __cplusplus
extern "C" {
#endif

// Length of the sequence a lead byte starts (1 for stray continuation/invalid bytes, as GLib's
// g_utf8_skip table).
static inline int g_utf8_skip_len_(unsigned char c)
{
    return c < 0xC0 ? 1 : c < 0xE0 ? 2 : c < 0xF0 ? 3 : c < 0xF8 ? 4 : c < 0xFC ? 5 : c < 0xFE ? 6 : 1;
}

#define g_utf8_next_char(p) ((char *)((p) + g_utf8_skip_len_(*(const unsigned char *)(p))))

static inline void g_free(void *p) { free(p); }

static inline gboolean g_unichar_validate(gunichar c)
{
    return c < 0x110000 && (c < 0xD800 || c > 0xDFFF);
}

static inline gint g_unichar_to_utf8(gunichar c, gchar *out)
{
    unsigned char buf[4];
    int n;
    if (c < 0x80) { buf[0] = (unsigned char)c; n = 1; }
    else if (c < 0x800) { buf[0] = 0xC0 | (c >> 6); buf[1] = 0x80 | (c & 0x3F); n = 2; }
    else if (c < 0x10000) { buf[0] = 0xE0 | (c >> 12); buf[1] = 0x80 | ((c >> 6) & 0x3F); buf[2] = 0x80 | (c & 0x3F); n = 3; }
    else { buf[0] = 0xF0 | (c >> 18); buf[1] = 0x80 | ((c >> 12) & 0x3F); buf[2] = 0x80 | ((c >> 6) & 0x3F); buf[3] = 0x80 | (c & 0x3F); n = 4; }
    if (out) memcpy(out, buf, (size_t)n);
    return n;
}

// Decodes the character at p (input assumed valid, as GLib's g_utf8_get_char).
static inline gunichar g_utf8_get_char(const gchar *p)
{
    const unsigned char *s = (const unsigned char *)p;
    if (s[0] < 0x80) return s[0];
    if (s[0] < 0xE0) return ((gunichar)(s[0] & 0x1F) << 6) | (s[1] & 0x3F);
    if (s[0] < 0xF0) return ((gunichar)(s[0] & 0x0F) << 12) | ((gunichar)(s[1] & 0x3F) << 6) | (s[2] & 0x3F);
    return ((gunichar)(s[0] & 0x07) << 18) | ((gunichar)(s[1] & 0x3F) << 12) | ((gunichar)(s[2] & 0x3F) << 6) | (s[3] & 0x3F);
}

// max_len < 0: NUL-terminated. Otherwise at most max_len bytes; a NUL inside them is invalid.
static inline gboolean g_utf8_validate(const gchar *str, gssize max_len, const gchar **end)
{
    const unsigned char *p = (const unsigned char *)str;
    const unsigned char *stop = max_len < 0 ? NULL : p + max_len;
    gboolean ok = 1;
    while (stop ? p < stop : *p) {
        if (*p == 0) { ok = 0; break; }
        utf8proc_int32_t cp;
        utf8proc_ssize_t avail = stop ? (utf8proc_ssize_t)(stop - p) : -1;
        utf8proc_ssize_t n = utf8proc_iterate(p, avail, &cp);
        if (n <= 0) { ok = 0; break; }
        p += n;
    }
    if (end) *end = (const gchar *)p;
    return ok;
}

static inline glong g_utf8_strlen(const gchar *p, gssize max)
{
    glong n = 0;
    const gchar *start = p;
    if (max < 0) {
        while (*p) { p = g_utf8_next_char(p); ++n; }
    } else {
        while (p < start + max && *p) {
            const gchar *next = g_utf8_next_char(p);
            if (next > start + max) break; // partial character at the end is not counted
            p = next;
            ++n;
        }
    }
    return n;
}

static inline gchar *g_utf8_offset_to_pointer(const gchar *str, glong offset)
{
    const gchar *p = str;
    if (offset >= 0) {
        while (offset-- > 0) p = g_utf8_next_char(p);
    } else {
        while (offset++ < 0) {
            do { --p; } while ((*(const unsigned char *)p & 0xC0) == 0x80);
        }
    }
    return (gchar *)p;
}

// Copies n characters of src and a terminating NUL into dest.
static inline gchar *g_utf8_strncpy(gchar *dest, const gchar *src, gsize n)
{
    const gchar *p = src;
    while (n-- > 0 && *p) p = g_utf8_next_char(p);
    memcpy(dest, src, (size_t)(p - src));
    dest[p - src] = '\0';
    return dest;
}

// Returns a malloc'd normalised copy, or NULL for invalid UTF-8.
static inline gchar *g_utf8_normalize(const gchar *str, gssize len, GNormalizeMode mode)
{
    int opts = UTF8PROC_STABLE;
    if (mode == G_NORMALIZE_ALL || mode == G_NORMALIZE_ALL_COMPOSE) opts |= UTF8PROC_COMPAT;
    opts |= (mode == G_NORMALIZE_DEFAULT_COMPOSE || mode == G_NORMALIZE_ALL_COMPOSE) ? UTF8PROC_COMPOSE : UTF8PROC_DECOMPOSE;
    if (len < 0) {
        opts |= UTF8PROC_NULLTERM;
        len = 0;
    }
    utf8proc_uint8_t *out = NULL;
    if (utf8proc_map((const utf8proc_uint8_t *)str, len, &out, (utf8proc_option_t)opts) < 0) return NULL;
    return (gchar *)out;
}

#ifdef __cplusplus
}
#endif
