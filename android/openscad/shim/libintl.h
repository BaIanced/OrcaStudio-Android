// Android has no gettext. OpenSCAD's core calls it for message translation (utils/printutils.h);
// untranslated (English) messages are what the app shows anyway.
#pragma once

#include <locale.h>

#ifdef __cplusplus
extern "C" {
#endif

static inline char *gettext(const char *msgid) { return (char *)msgid; }
static inline char *dgettext(const char *domain, const char *msgid) { (void)domain; return (char *)msgid; }
static inline char *dcgettext(const char *domain, const char *msgid, int category)
{
    (void)domain; (void)category;
    return (char *)msgid;
}
static inline char *ngettext(const char *msgid, const char *plural, unsigned long n)
{
    return (char *)(n == 1 ? msgid : plural);
}
static inline char *textdomain(const char *domain) { return (char *)domain; }
static inline char *bindtextdomain(const char *domain, const char *dir) { (void)domain; return (char *)dir; }
static inline char *bind_textdomain_codeset(const char *domain, const char *codeset)
{
    (void)domain;
    return (char *)codeset;
}

#ifdef __cplusplus
}
#endif
